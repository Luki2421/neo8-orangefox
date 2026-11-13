package pl.luki.frankenflasher.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
            val intf = findInterface(dev) ?: throw IOException("Urzadzenie nie ma interfejsu fastboot")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
                }
            }
            if (inEp == null || outEp == null) throw IOException("Brak endpointow bulk")
            val conn = mgr.openDevice(dev) ?: throw IOException("Nie mozna otworzyc urzadzenia USB")
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("Nie mozna przejac interfejsu USB")
            }
            return UsbFastbootTransport(conn, intf, inEp, outEp, dev)
        }
    }

    fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int = 30_000) {
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
                throw IOException("Blad zapisu USB ($n, paczka ${want / 1024} KB)")
            }
            o += n
            rem -= n
        }
    }

    /** Zwraca liczbe bajtow albo -1 przy timeoucie. */
    fun read(buf: ByteArray, timeoutMs: Int): Int =
        conn.bulkTransfer(epIn, buf, 0, buf.size, timeoutMs)

    override fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        conn.close()
    }
}
