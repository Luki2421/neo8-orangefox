package pl.luki.frankenflasher.fastboot

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
            if (left <= 0) throw FastbootException("Brak odpowiedzi (timeout)")
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
        if (c != "OKAY") throw FastbootException("$cmd: nieoczekiwana odpowiedz $c$p")
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
        if (c != "DATA") throw FastbootException("download: nieoczekiwana odpowiedz $c$p")
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
        private val b = ByteArray(1 shl 20)
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
            log("Wysylanie ${src.name} (${src.size / (1 shl 20)} MB) -> $partition")
            downloadThen(src, "flash:$partition", 15 * 60_000, progress)
        } else if (!sparse) {
            val pieces = Sparse.plan(src.size, max)
            val total = pieces.sumOf { it.numBlk * Sparse.BLK }
            log("${src.name}: ${src.size / (1 shl 20)} MB -> ${pieces.size} czesci sparse -> $partition")
            src.open().use { input ->
                pieces.forEachIndexed { i, p ->
                    log("  czesc ${i + 1}/${pieces.size}")
                    val head = Sparse.head(p)
                    val tail = Sparse.tail(p)
                    startDownload(p.byteSize)
                    val sink = Sink()
                    sink.write(head, 0, head.size)
                    copyTo(sink, input, p.numBlk * Sparse.BLK, buf) { n -> done += n; progress(done, total) }
                    if (tail.isNotEmpty()) sink.write(tail, 0, tail.size)
                    sink.flush()
                    finishDownload()
                    command("flash:$partition", 15 * 60_000)
                }
            }
        } else {
            val plan = src.open().use { SparseResplitter.plan(it, max) }
            val total = src.size
            log("${src.name}: sparse ${src.size / (1 shl 20)} MB -> ${plan.pieces.size} czesci -> $partition")
            src.open().use { input ->
                val pos = longArrayOf(0)
                plan.pieces.forEachIndexed { i, p ->
                    log("  czesc ${i + 1}/${plan.pieces.size}")
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
