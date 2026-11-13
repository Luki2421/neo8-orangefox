package pl.luki.frankenflasher.usb

import pl.luki.frankenflasher.tr
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import android.os.Build
import java.io.IOException

/** Transport USB do urzadzenia w trybie fastboot / fastbootd (interfejs FF/42/03). */
class UsbFastbootTransport private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    val device: UsbDevice,
) : AutoCloseable {

    companion object {
        /** Kolejne rozmiary paczek USB: jesli kontroler odrzuci wieksza (-1 od razu), schodzimy nizej. */
        private val CHUNKS = if (Build.VERSION.SDK_INT >= 28) intArrayOf(64 * 1024, 16 * 1024, 4 * 1024) else intArrayOf(16 * 1024, 4 * 1024)
        /** Zapamietany rozmiar, ktory dziala na tym telefonie (wspolny dla kolejnych polaczen). */
        @Volatile var chunkIdx = 0
        val chunkSize: Int get() = CHUNKS[chunkIdx]
        var onChunkDowngrade: ((Int) -> Unit)? = null
        /** Szybki zapis: kilka asynchronicznych zadan USB naraz (UsbRequest). Wylaczany automatycznie przy bledzie. */
        @Volatile var asyncEnabled = true
        var onAsyncDisabled: ((String) -> Unit)? = null
        private const val ASYNC_SIZE = 256 * 1024
        private const val ASYNC_DEPTH = 4

        fun findInterface(dev: UsbDevice): UsbInterface? {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x03) {
                    return itf
                }
            }
            return null
        }

        fun open(mgr: UsbManager, dev: UsbDevice): UsbFastbootTransport {
            val intf = findInterface(dev) ?: throw IOException(tr("Urzadzenie nie ma interfejsu fastboot", "Device has no fastboot interface"))
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
                }
            }
            if (inEp == null || outEp == null) throw IOException(tr("Brak endpointow bulk", "No bulk endpoints"))
            val conn = mgr.openDevice(dev) ?: throw IOException(tr("Nie mozna otworzyc urzadzenia USB", "Cannot open USB device"))
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException(tr("Nie mozna przejac interfejsu USB", "Cannot claim USB interface"))
            }
            return UsbFastbootTransport(conn, intf, inEp, outEp, dev).also { it.clearHalt() }
        }
    }

    private var reqs: List<Pair<UsbRequest, ByteBuffer>>? = null

    private fun asyncPool(): List<Pair<UsbRequest, ByteBuffer>>? {
        reqs?.let { return it }
        return try {
            List(ASYNC_DEPTH) {
                val r = UsbRequest()
                if (!r.initialize(conn, epOut)) throw IOException("initialize")
                r to ByteBuffer.allocateDirect(ASYNC_SIZE)
            }.also { reqs = it }
        } catch (e: Exception) { null }
    }

    /**
     * Zapis potokowy: do ASYNC_DEPTH paczek po 256 KB w locie. Zwraca false, gdy pierwsza paczka
     * zostala odrzucona (nic nie wyslano) - wtedy wolajacy przechodzi na zwykly bulkTransfer.
     */
    private fun writeAsync(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Boolean {
        val pool = asyncPool() ?: return false
        val free = ArrayDeque(pool)
        var inFlight = 0
        var o = off; val end = off + len
        var sentAny = false
        while (o < end || inFlight > 0) {
            while (o < end && free.isNotEmpty()) {
                val (r, bb) = free.removeFirst()
                val n = minOf(ASYNC_SIZE, end - o)
                bb.clear(); bb.put(buf, o, n); bb.flip()
                @Suppress("DEPRECATION")
                val ok = if (Build.VERSION.SDK_INT >= 26) r.queue(bb) else r.queue(bb, n)
                if (!ok) {
                    if (!sentAny && inFlight == 0) return false
                    clearHalt()
                    throw IOException(tr("Blad kolejki USB po ${(o - off) / 1024} KB z ${len / 1024} KB", "USB queue error after ${(o - off) / 1024} KB of ${len / 1024} KB"))
                }
                r.clientData = bb
                o += n; inFlight++; sentAny = true
            }
            val done = try {
                if (Build.VERSION.SDK_INT >= 26) conn.requestWait(timeoutMs.toLong()) else conn.requestWait()
            } catch (e: java.util.concurrent.TimeoutException) {
                null
            } ?: run {
                pool.forEach { try { it.first.cancel() } catch (_: Exception) {} }
                clearHalt()
                throw IOException(tr("Blad zapisu USB (async) po ${(o - off) / 1024} KB z ${len / 1024} KB", "USB write error (async) after ${(o - off) / 1024} KB of ${len / 1024} KB"))
            }
            val pair = pool.first { it.first === done }
            free.addLast(pair); inFlight--
        }
        return true
    }

    fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int = 30_000) {
        if (asyncEnabled && len >= 64 * 1024 && Build.VERSION.SDK_INT >= 26) {
            if (writeAsync(buf, off, len, timeoutMs)) return
            asyncEnabled = false
            onAsyncDisabled?.invoke("kontroler nie przyjal zadan asynchronicznych")
        }
        var o = off
        var rem = len
        while (rem > 0) {
            val want = minOf(rem, CHUNKS[chunkIdx])
            val t0 = System.nanoTime()
            val n = conn.bulkTransfer(epOut, buf, o, want, timeoutMs)
            if (n <= 0) {
                val fast = (System.nanoTime() - t0) < 1_000_000_000L
                // Natychmiastowe -1 = kontroler hosta nie przyjal tak duzej paczki; nic nie poszlo, ponawiamy mniejsza.
                if (fast && want > 4 * 1024 && chunkIdx < CHUNKS.size - 1) {
                    chunkIdx++
                    onChunkDowngrade?.invoke(CHUNKS[chunkIdx])
                    continue
                }
                clearHalt()
                throw IOException(tr("Blad zapisu USB ($n) po ${(o - off) / 1024} KB z ${len / 1024} KB", "USB write error ($n) after ${(o - off) / 1024} KB of ${len / 1024} KB"))
            }
            o += n
            rem -= n
        }
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT) na obu endpointach - odblokowuje rure po bledzie transmisji. */
    fun clearHalt() {
        for (ep in arrayOf(epOut, epIn)) {
            try { conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000) } catch (_: Exception) {}
        }
    }

    /** Zwraca liczbe bajtow albo -1 przy timeoucie. */
    fun read(buf: ByteArray, timeoutMs: Int): Int =
        conn.bulkTransfer(epIn, buf, 0, buf.size, timeoutMs)

    override fun close() {
        reqs?.forEach { try { it.first.close() } catch (_: Exception) {} }
        reqs = null
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        conn.close()
    }
}
