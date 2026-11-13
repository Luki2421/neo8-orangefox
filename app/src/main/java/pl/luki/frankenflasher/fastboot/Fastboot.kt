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

    /** Wgrywa obraz na partycje. Obrazy raw wieksze niz max-download-size sa dzielone na kawalki sparse. */
    fun flash(partition: String, src: ImageSource, progress: (Long, Long) -> Unit) {
        val max = try { maxDownloadSize() } catch (e: Exception) { 256L shl 20 }
        val sparse = src.open().use { Sparse.isSparse(it) }
        val buf = ByteArray(1 shl 20)
        var done = 0L
        if (src.size <= max) {
            log("Wysylanie ${src.name} (${src.size / (1 shl 20)} MB)...")
            startDownload(src.size)
            src.open().use { copy(it, src.size, buf) { n -> done += n; progress(done, src.size) } }
            finishDownload()
            log("Zapisywanie $partition...")
            command("flash:$partition", 15 * 60_000)
        } else if (!sparse) {
            val pieces = Sparse.plan(src.size, max)
            val total = pieces.sumOf { it.numBlk * Sparse.BLK }
            log("${src.name}: ${src.size / (1 shl 20)} MB > limit ${max / (1 shl 20)} MB -> ${pieces.size} czesci sparse")
            src.open().use { input ->
                pieces.forEachIndexed { i, p ->
                    log("Czesc ${i + 1}/${pieces.size}...")
                    val head = Sparse.head(p)
                    val tail = Sparse.tail(p)
                    startDownload(p.byteSize)
                    t.write(head, 0, head.size)
                    copy(input, p.numBlk * Sparse.BLK, buf) { n -> done += n; progress(done, total) }
                    if (tail.isNotEmpty()) t.write(tail, 0, tail.size)
                    finishDownload()
                    command("flash:$partition", 15 * 60_000)
                }
            }
        } else {
            throw FastbootException("Obraz sparse wiekszy niz max-download-size (${max / (1 shl 20)} MB) - obsluga w etapie 2")
        }
        log("OK: $partition")
    }
}
