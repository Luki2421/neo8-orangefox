package pl.luki.frankenflasher.fastboot

import pl.luki.frankenflasher.tr
import pl.luki.frankenflasher.usb.UsbFastbootTransport
import java.io.InputStream
import java.util.Locale

class FastbootException(msg: String) : Exception(msg)

interface ImageSource {
    val name: String
    val size: Long
    fun open(): InputStream
}

/** Protokol fastboot (bootloader i fastbootd). */
class Fastboot(private val t: UsbFastbootTransport, private val log: (String) -> Unit) {

    companion object { const val PIECE_LIMIT = 64L shl 20 }

    private val rbuf = ByteArray(256)

    private fun send(cmd: String) {
        val b = cmd.toByteArray(Charsets.US_ASCII)
        t.write(b, 0, b.size, 5_000)
    }

    private fun readResponse(timeoutMs: Long, onInfo: (String) -> Unit = { log("  (bootloader) $it") }): Pair<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw FastbootException(tr("Brak odpowiedzi (timeout)", "No response (timeout)"))
            val n = t.read(rbuf, minOf(left, 5_000L).toInt())
            if (n < 4) continue
            val s = String(rbuf, 0, n, Charsets.US_ASCII)
            val code = s.substring(0, 4)
            val payload = s.substring(4)
            when (code) {
                "INFO", "TEXT" -> onInfo(payload)
                "OKAY", "FAIL", "DATA" -> return code to payload
                else -> log("  ? $s")
            }
        }
    }

    fun command(cmd: String, timeoutMs: Long = 15_000): String {
        send(cmd)
        val (c, p) = readResponse(timeoutMs)
        if (c == "FAIL") throw FastbootException("$cmd: FAILED ($p)")
        if (c != "OKAY") throw FastbootException(tr("$cmd: nieoczekiwana odpowiedz $c$p", "$cmd: unexpected response $c$p"))
        return p
    }

    fun getVar(name: String): String = command("getvar:$name")

    fun getVarAll(): List<String> {
        val lines = ArrayList<String>()
        send("getvar:all")
        val (c, p) = readResponse(30_000) { lines += it }
        if (c == "FAIL") throw FastbootException("getvar:all: FAILED ($p)")
        return lines
    }

    fun maxDownloadSize(): Long {
        val v = getVar("max-download-size").trim()
        return when {
            v.startsWith("0x", true) -> v.substring(2).toLong(16)
            else -> v.toLongOrNull() ?: v.toLong(16)
        }
    }

    private fun startDownload(size: Long) {
        send(String.format(Locale.ROOT, "download:%08x", size))
        val (c, p) = readResponse(15_000)
        if (c == "FAIL") throw FastbootException("download: FAILED ($p)")
        if (c != "DATA") throw FastbootException(tr("download: nieoczekiwana odpowiedz $c$p", "download: unexpected response $c$p"))
    }

    private fun finishDownload() {
        val (c, p) = readResponse(120_000)
        if (c != "OKAY") throw FastbootException("download: $c $p")
    }

    /**
     * Laczy naglowki sparse i dane w jeden ciagly strumien. Wysylamy tylko pelne bloki 1 MB,
     * a krotki pakiet dopiero na samym koncu - fastbootd zawiesza sie, gdy krotki pakiet
     * (np. 40-bajtowy naglowek) przyjdzie w srodku pobierania.
     */
    private inner class Sink {
        private val b = ByteArray(4 shl 20)
        private var n = 0
        fun write(src: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                val k = minOf(l, b.size - n)
                System.arraycopy(src, o, b, n, k)
                n += k; o += k; l -= k
                if (n == b.size) { t.write(b, 0, n, 60_000); n = 0 }
            }
        }
        fun flush() { if (n > 0) { t.write(b, 0, n, 60_000); n = 0 } }
    }

    private fun copyTo(sink: Sink, input: InputStream, count: Long, buf: ByteArray, onBytes: (Int) -> Unit) {
        var left = count
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            var got = 0
            while (got < want) {
                val r = input.read(buf, got, want - got)
                if (r < 0) break
                got += r
            }
            if (got < want) java.util.Arrays.fill(buf, got, want, 0)
            sink.write(buf, 0, want)
            left -= want
            onBytes(want)
        }
    }

    private fun copy(input: InputStream, count: Long, buf: ByteArray, onBytes: (Int) -> Unit) {
        var left = count
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            var got = 0
            while (got < want) {
                val r = input.read(buf, got, want - got)
                if (r < 0) break
                got += r
            }
            if (got < want) java.util.Arrays.fill(buf, got, want, 0) // dopelnienie ostatniego bloku zerami
            t.write(buf, 0, want, 60_000)
            left -= want
            onBytes(want)
        }
    }

    /** Rozmiar obrazu po rozpakowaniu (dla sparse: total_blks * blk_sz). */
    fun logicalSize(src: ImageSource): Long = src.open().use { i ->
        val h = ByteArray(28)
        var n = 0
        while (n < 28) { val r = i.read(h, n, 28 - n); if (r < 0) break; n += r }
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (n == 28 && bb.getInt(0) == Sparse.MAGIC) (bb.getInt(16).toLong() and 0xFFFFFFFFL) * bb.getInt(12) else src.size
    }

    fun hasSlot(part: String): Boolean = try { getVar("has-slot:$part").trim() == "yes" } catch (_: Exception) { false }
    fun isLogical(part: String): Boolean = try { getVar("is-logical:$part").trim() == "yes" } catch (_: Exception) { false }
    fun partitionExists(part: String): Boolean = try { getVar("partition-size:$part").isNotBlank() } catch (_: Exception) { false }

    /**
     * Wysyla kawalek obrazu raw jako sparse: bloki zerowe jako FILL(0) - telefon sam zapisuje zera,
     * przez USB idzie tylko 16 B zamiast 4 KB na blok. Zwraca liczbe bajtow zer.
     */
    private fun sendSparsePiece(p: Sparse.Piece, data: ByteArray): Long {
        val blk = Sparse.BLK
        val n = p.numBlk.toInt()
        // biegi: true = zera
        val runs = ArrayList<Pair<Boolean, Int>>()
        fun isZero(b: Int): Boolean { val o = b * blk; for (k in o until o + blk) if (data[k].toInt() != 0) return false; return true }
        var b = 0
        while (b < n) {
            val z = isZero(b)
            var e = b + 1
            while (e < n && isZero(e) == z) e++
            // krotkie biegi zer (<16 blokow) wysylamy jako RAW - mniej chunkow
            if (z && e - b < 16 && runs.isNotEmpty() && !runs.last().first) runs[runs.size - 1] = false to (runs.last().second + (e - b))
            else if (!z && runs.isNotEmpty() && !runs.last().first) runs[runs.size - 1] = false to (runs.last().second + (e - b))
            else runs += z to (e - b)
            b = e
        }
        val before = p.startBlk > 0
        val after = p.startBlk + p.numBlk < p.totalBlk
        val chunks = runs.size + (if (before) 1 else 0) + (if (after) 1 else 0)
        var size = 28L
        if (before) size += 12
        if (after) size += 12
        for ((z, c) in runs) size += if (z) 16 else 12L + c.toLong() * blk
        startDownload(size)
        val sink = Sink()
        val hdr = java.nio.ByteBuffer.allocate(28).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(Sparse.MAGIC).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(blk).putInt(p.totalBlk.toInt()).putInt(chunks).putInt(0)
        sink.write(hdr.array(), 0, 28)
        fun ch(type: Int, blocks: Long, total: Long, extra: ByteArray? = null) {
            val c = java.nio.ByteBuffer.allocate(12 + (extra?.size ?: 0)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            c.putShort(type.toShort()).putShort(0).putInt(blocks.toInt()).putInt(total.toInt())
            if (extra != null) c.put(extra)
            sink.write(c.array(), 0, c.capacity())
        }
        if (before) ch(0xCAC3, p.startBlk, 12)
        var pos = 0; var zeros = 0L
        for ((z, c) in runs) {
            if (z) { ch(0xCAC2, c.toLong(), 16, ByteArray(4)); zeros += c.toLong() * blk }
            else { ch(0xCAC1, c.toLong(), 12L + c.toLong() * blk); sink.write(data, pos * blk, c * blk) }
            pos += c
        }
        if (after) ch(0xCAC3, p.totalBlk - p.startBlk - p.numBlk, 12)
        sink.flush()
        finishDownload()
        return zeros
    }

    /** Wysyla caly obraz (<= max-download-size) i wykonuje [then]. */
    fun downloadThen(src: ImageSource, then: String, timeoutMs: Long, progress: (Long, Long) -> Unit): String {
        val buf = ByteArray(1 shl 20)
        var done = 0L
        startDownload(src.size)
        src.open().use { copy(it, src.size, buf) { n -> done += n; progress(done, src.size) } }
        finishDownload()
        return command(then, timeoutMs)
    }

    /** Wgrywa obraz na partycje. Duze obrazy (raw i sparse) sa dzielone na czesci. */
    fun flash(partition: String, src: ImageSource, progress: (Long, Long) -> Unit) {
        // Mniejsze czesci = mniej danych do powtorzenia po bledzie i mniejsze obciazenie RAM w fastbootd.
        val devMax = try { maxDownloadSize() } catch (e: Exception) { 256L shl 20 }
        val max = minOf(devMax, PIECE_LIMIT)
        val sparse = src.open().use { Sparse.isSparse(it) }
        val buf = ByteArray(1 shl 20)
        var done = 0L
        if (src.size <= max) {
            log(tr("Wysylanie ${src.name} (${src.size / (1 shl 20)} MB) -> $partition", "Sending ${src.name} (${src.size / (1 shl 20)} MB) -> $partition"))
            downloadThen(src, "flash:$partition", 15 * 60_000, progress)
        } else if (!sparse) {
            val pieces = Sparse.plan(src.size, max)
            val total = pieces.sumOf { it.numBlk * Sparse.BLK }
            log(tr("${src.name}: ${src.size / (1 shl 20)} MB -> ${pieces.size} czesci sparse -> $partition", "${src.name}: ${src.size / (1 shl 20)} MB -> ${pieces.size} sparse pieces -> $partition"))
            val pieceBuf = ByteArray((pieces.maxOf { it.numBlk } * Sparse.BLK).toInt())
            var zeroTotal = 0L
            src.open().use { input ->
                pieces.forEachIndexed { i, p ->
                    log(tr("  czesc ${i + 1}/${pieces.size}", "  piece ${i + 1}/${pieces.size}"))
                    val len = (p.numBlk * Sparse.BLK).toInt()
                    var got = 0
                    while (got < len) { val r = input.read(pieceBuf, got, len - got); if (r < 0) break; got += r }
                    if (got < len) java.util.Arrays.fill(pieceBuf, got, len, 0)
                    zeroTotal += sendSparsePiece(p, pieceBuf)
                    done += len; progress(done, total)
                    command("flash:$partition", 15 * 60_000)
                }
            }
            if (zeroTotal > 0) log(tr("  ${src.name}: ${zeroTotal / (1 shl 20)} MB zer wyslanych jako FILL (bez transferu)", "  ${src.name}: ${zeroTotal / (1 shl 20)} MB of zeros sent as FILL (no transfer)"))
        } else {
            val plan = src.open().use { SparseResplitter.plan(it, max) }
            val total = src.size
            log(tr("${src.name}: sparse ${src.size / (1 shl 20)} MB -> ${plan.pieces.size} czesci -> $partition", "${src.name}: sparse ${src.size / (1 shl 20)} MB -> ${plan.pieces.size} pieces -> $partition"))
            src.open().use { input ->
                val pos = longArrayOf(0)
                plan.pieces.forEachIndexed { i, p ->
                    log(tr("  czesc ${i + 1}/${plan.pieces.size}", "  piece ${i + 1}/${plan.pieces.size}"))
                    startDownload(p.byteSize(plan.totalBlocks))
                    val sink = Sink()
                    plan.writePiece(p, input, pos, { b, o, l -> sink.write(b, o, l) }) { n -> done += n; progress(done, total) }
                    sink.flush()
                    finishDownload()
                    command("flash:$partition", 15 * 60_000)
                }
            }
        }
        log("OK: $partition")
    }
}
