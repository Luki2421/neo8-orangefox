package pl.luki.frankenflasher.franken

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Nazwa pakietu aplikacji (np. com.heytap.cloud) z APK w obrazie: AndroidManifest.xml jest zwykle
 * pierwszym wpisem APK, wiec wystarczy czytac poczatek pliku strumieniowo (dump.erofs --cat)
 * i przerwac po manifescie - bez kopiowania calego APK.
 */
object ApkPackage {
    /** Czyta strumien ZIP do AndroidManifest.xml; null gdy go nie ma albo strumien nie jest ZIP-em. */
    fun fromApkStream(ins: InputStream): String? = try {
        ZipInputStream(ins).use { z ->
            var e = z.nextEntry
            while (e != null && e.name != "AndroidManifest.xml") e = z.nextEntry
            e?.let { fromManifest(z.readBytes()) }
        }
    } catch (_: Exception) { null }

    /** Atrybut package elementu <manifest> z binarnego XML (AXML). */
    fun fromManifest(axml: ByteArray): String? {
        val b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        if (axml.size < 8 || b.getShort(0).toInt() != 0x0003) return null
        var strings: List<String> = emptyList()
        var pos = b.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= axml.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val hdr = b.getShort(pos + 2).toInt() and 0xFFFF
            val size = b.getInt(pos + 4)
            if (size < 8 || pos + size > axml.size) return null
            when (type) {
                0x0001 -> strings = stringPool(b, pos, hdr)
                0x0102 -> {
                    val ext = pos + hdr
                    val name = strings.getOrNull(b.getInt(ext + 4))
                    if (name == "manifest") {
                        val attrStart = b.getShort(ext + 8).toInt() and 0xFFFF
                        val attrSize = b.getShort(ext + 10).toInt() and 0xFFFF
                        val count = b.getShort(ext + 12).toInt() and 0xFFFF
                        for (i in 0 until count) {
                            val a = ext + attrStart + i * attrSize
                            if (strings.getOrNull(b.getInt(a + 4)) != "package") continue
                            val raw = b.getInt(a + 8)
                            val dataType = b.get(a + 15).toInt() and 0xFF
                            val data = b.getInt(a + 16)
                            return strings.getOrNull(if (raw >= 0) raw else if (dataType == 0x03) data else -1)
                        }
                        return null
                    }
                }
            }
            pos += size
        }
        return null
    }

    private fun stringPool(b: ByteBuffer, start: Int, hdr: Int): List<String> {
        val count = b.getInt(start + 8)
        val utf8 = b.getInt(start + 16) and 0x100 != 0
        val stringsStart = start + b.getInt(start + 20)
        return List(count) { i ->
            var p = stringsStart + b.getInt(start + hdr + i * 4)
            if (utf8) {
                // dlugosc w znakach, potem w bajtach (1 albo 2 bajty kazda)
                p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = b.get(p).toInt() and 0xFF
                if (len and 0x80 != 0) { len = (len and 0x7F shl 8) or (b.get(p + 1).toInt() and 0xFF); p += 2 } else p += 1
                String(b.array(), p, len, Charsets.UTF_8)
            } else {
                var len = b.getShort(p).toInt() and 0xFFFF
                if (len and 0x8000 != 0) { len = (len and 0x7FFF shl 16) or (b.getShort(p + 2).toInt() and 0xFFFF); p += 4 } else p += 2
                String(b.array(), p, len * 2, Charsets.UTF_16LE)
            }
        }
    }
}
