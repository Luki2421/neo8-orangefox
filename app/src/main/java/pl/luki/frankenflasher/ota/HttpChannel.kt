package pl.luki.frankenflasher.ota

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Plik na serwerze HTTP jako FileChannel tylko do odczytu (zapytania Range) - pozwala czytac ZIP/payload.bin
 * i wyciagac pojedyncze partycje bez pobierania calego OTA.
 * Odczyty sekwencyjne korzystaja z jednego otwartego polaczenia; skok w inne miejsce otwiera nowe.
 */
class HttpChannel(private val url: String) : FileChannel() {

    private val size: Long
    private var stream: InputStream? = null
    private var conn: HttpURLConnection? = null
    private var streamPos = -1L
    private var position = 0L
    @Volatile var bytesRead = 0L; private set

    init {
        val c = open(0, 0)
        try {
            val cr = c.getHeaderField("Content-Range")
            size = cr?.substringAfterLast('/')?.toLongOrNull()
                ?: if (c.responseCode == 200) c.contentLengthLong else -1L
            if (c.responseCode != 206) throw IOException("Serwer nie obsluguje odczytu fragmentow (HTTP ${c.responseCode})")
            if (size <= 0) throw IOException("Nieznany rozmiar pliku na serwerze")
        } finally { c.disconnect() }
    }

    private fun open(from: Long, to: Long?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("Range", "bytes=$from-${to ?: ""}")
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun closeStream() {
        try { stream?.close() } catch (_: Exception) {}
        conn?.disconnect()
        stream = null; conn = null; streamPos = -1
    }

    @Synchronized
    override fun read(dst: ByteBuffer, pos: Long): Int {
        if (pos >= size) return -1
        if (!dst.hasRemaining()) return 0
        var attempt = 0
        while (true) {
            try {
                if (stream == null || streamPos != pos) {
                    closeStream()
                    val c = open(pos, null)
                    if (c.responseCode != 206) { c.disconnect(); throw IOException("HTTP ${c.responseCode}") }
                    conn = c; stream = c.inputStream; streamPos = pos
                }
                val want = minOf(dst.remaining().toLong(), size - pos).toInt()
                val tmp = ByteArray(minOf(want, 1 shl 20))
                val n = stream!!.read(tmp, 0, tmp.size)
                if (n < 0) { closeStream(); throw IOException("Polaczenie zamkniete przedwczesnie") }
                dst.put(tmp, 0, n)
                streamPos += n
                bytesRead += n
                return n
            } catch (e: IOException) {
                closeStream()
                if (++attempt >= 5) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }

    override fun read(dst: ByteBuffer): Int { val n = read(dst, position); if (n > 0) position += n; return n }
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        var t = 0L
        for (i in offset until offset + length) { val n = read(dsts[i]); if (n < 0) return if (t == 0L) -1 else t; t += n; if (dsts[i].hasRemaining()) break }
        return t
    }
    override fun size() = size
    override fun position() = position
    override fun position(newPosition: Long): FileChannel { position = newPosition; return this }
    override fun implCloseChannel() { synchronized(this) { closeStream() } }

    override fun write(src: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = throw java.nio.channels.NonWritableChannelException()
    override fun write(src: ByteBuffer, position: Long): Int = throw java.nio.channels.NonWritableChannelException()
    override fun truncate(size: Long): FileChannel = throw java.nio.channels.NonWritableChannelException()
    override fun force(metaData: Boolean) {}
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = throw UnsupportedOperationException()
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = throw java.nio.channels.NonWritableChannelException()
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = throw UnsupportedOperationException()
}
