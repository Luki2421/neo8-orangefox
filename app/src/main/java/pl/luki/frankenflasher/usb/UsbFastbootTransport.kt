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
        private val MAX_CHUNK = if (Build.VERSION.SDK_INT >= 28) 256 * 1024 else 16 * 1024

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
            val n = conn.bulkTransfer(epOut, buf, o, minOf(rem, MAX_CHUNK), timeoutMs)
            if (n <= 0) throw IOException("Blad zapisu USB ($n)")
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
