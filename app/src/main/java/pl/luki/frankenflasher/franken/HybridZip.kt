package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Hybrid super flasher: jeden ZIP do flashowania w recovery (OrangeFox / AERA / TWRP)
 * i - po rozpakowaniu - skryptami fastboot na PC.
 *
 * W recovery update-binary zapisuje obrazy prosto z ZIP-a przez dd (obrazy sa STORED
 * i wyrownane do 1 MB): partycje fizyczne na oba sloty, naglowek super (metadane liblp)
 * i partycje logiczne w ich miejsca w super. Bez simg2img i bez 16 GB pliku super.img.
 */
class HybridZip(private val log: (String) -> Unit, private val progress: (Long, Long) -> Unit = { _, _ -> }) {

    private class Entry(val name: String, val file: File?, var bytes: ByteArray?, val align: Long) {
        val size: Long get() = file?.length() ?: bytes!!.size.toLong()
        var headerOffset = 0L
        var dataOffset = 0L
        var crc = 0L
        val zip64 get() = size >= 0xFFFFFFFFL
    }

    companion object {
        private const val MB = 1L shl 20
        private val PHYS_GROUPS = listOf("CRITICAL", "EXTRA", "MODEM", "BOOTLOADER")

        /** --disable-verity --disable-verification: flagi AVB (offset 120, big-endian) |= 3. */
        fun patchVbmeta(d: ByteArray): ByteArray {
            if (d.size < 124 || String(d, 0, 4, Charsets.US_ASCII) != "AVB0") return d
            val c = d.copyOf()
            val bb = ByteBuffer.wrap(c).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(120, bb.getInt(120) or 3)
            return c
        }
    }

    fun build(folder: File, superSizeIn: Long?, disableVbmeta: Boolean, out: File): File {
        val st = SuperFlasher(log).setup(folder, superSizeIn)
        val plan = SuperBuilder(log).plan(st.images, st.group, st.groupMax, st.superSize)
        val phys = PHYS_GROUPS.flatMap { g ->
            File(folder, g).listFiles { f -> f.isFile && f.name.endsWith(".img") && f.length() > 0 }.orEmpty().sortedBy { it.name }.map { g to it }
        }.sortedBy { (g, f) -> if (g == "BOOTLOADER" && f.name.startsWith("vbmeta")) 1 else 0 }

        out.parentFile?.mkdirs()
        val need = phys.sumOf { it.second.length() } + st.images.values.sumOf { it.length() } + 256 * MB
        if (out.parentFile!!.usableSpace < need) throw IOException(tr(
            "Za malo miejsca na ZIP: wolne ${out.parentFile!!.usableSpace / (1 shl 30)} GB, potrzeba ${need / (1 shl 30) + 1} GB",
            "Not enough storage for ZIP: free ${out.parentFile!!.usableSpace / (1 shl 30)} GB, need ${need / (1 shl 30) + 1} GB"))

        // --- wpisy ---
        val entries = ArrayList<Entry>()
        val physEntries = ArrayList<Pair<String, Entry>>() // partycja -> wpis
        for ((g, f) in phys) {
            val name = f.name.removeSuffix(".img")
            val e = if (disableVbmeta && name.startsWith("vbmeta")) Entry("$g/${f.name}", null, patchVbmeta(f.readBytes()), MB)
            else Entry("$g/${f.name}", f, null, MB)
            entries += e; physEntries += name to e
        }
        val metaEntry = Entry("SUPER/super_meta.img", null, plan.head, MB)
        entries += metaEntry
        val logical = ArrayList<Pair<String, Entry>>()
        for (n in st.images.keys.sorted()) {
            val e = Entry("SYSTEM/$n.img", st.images.getValue(n), null, MB)
            entries += e; logical += n to e
        }
        val layoutTxt = plan.offsets.entries.sortedBy { it.value }.joinToString("\n") { (n, off) -> "${n}_a $off ${st.images.getValue(n).length()}" } + "\n"
        entries += Entry("SUPER/super_layout.txt", null, layoutTxt.toByteArray(), 0)
        entries += Entry("flash_all.bat", null, pcScriptBat(phys, st.images.keys.sorted()).toByteArray(), 0)
        entries += Entry("flash_all.sh", null, pcScriptSh(phys, st.images.keys.sorted()).toByteArray(), 0)
        entries += Entry("META-INF/com/google/android/updater-script", null, "#FRANKEN-HYBRID\n".toByteArray(), 0)
        val binEntry = Entry("META-INF/com/google/android/update-binary", null, ByteArray(0), 0)
        entries += binEntry

        // --- zapis: najpierw obrazy (przesuniecia znane), skrypt recovery na koncu ---
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        val total = entries.sumOf { it.file?.length() ?: 0L }
        var done = 0L
        RandomAccessFile(tmp, "rw").use { raf ->
            val buf = ByteArray(4 shl 20)
            for (e in entries) {
                if (e === binEntry) e.bytes = recoveryScript(physEntries, metaEntry, logical, plan, st.superSize).toByteArray()
                writeLocal(raf, e)
                val crc = CRC32()
                if (e.file != null) {
                    e.file.inputStream().use { i ->
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); crc.update(buf, 0, n)
                            done += n; progress(done, total)
                        }
                    }
                } else { raf.write(e.bytes!!); crc.update(e.bytes!!) }
                e.crc = crc.value
                val end = raf.filePointer
                raf.seek(e.headerOffset + 14); raf.write(le32(e.crc))
                raf.seek(end)
            }
            writeCentral(raf, entries)
        }
        out.delete()
        if (!tmp.renameTo(out)) throw IOException("rename ${tmp.name}")
        log(tr("Hybrid ZIP gotowy: ${out.path} (${out.length() / MB} MB, ${entries.size} plikow)",
            "Hybrid ZIP ready: ${out.path} (${out.length() / MB} MB, ${entries.size} files)"))
        return out
    }

    // ------------------------------------------------------------ ZIP (STORED, ZIP64, wyrownanie)

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    private fun writeLocal(raf: RandomAccessFile, e: Entry) {
        val name = e.name.toByteArray(Charsets.UTF_8)
        val z64 = if (e.zip64) 20 else 0
        var start = raf.filePointer
        val extra = z64
        if (e.align > 0) {
            // przerwa przed naglowkiem tak, by dane zaczynaly sie na granicy 1 MB (czytniki uzywaja katalogu centralnego)
            val dataAt = start + 30 + name.size + z64
            val gap = (e.align - dataAt % e.align) % e.align
            if (gap > 0) { raf.write(ByteArray(gap.toInt())); start += gap }
        }
        e.headerOffset = start
        val b = ByteBuffer.allocate(30 + name.size + extra).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x04034b50).putShort((if (e.zip64) 45 else 20).toShort()).putShort(0x0800).putShort(0)
            .putShort(0).putShort(0x21) // czas/data DOS (1980-01-01)
            .putInt(0) // CRC uzupelniany po zapisie danych
        if (e.zip64) b.putInt(-1).putInt(-1) else b.putInt(e.size.toInt()).putInt(e.size.toInt())
        b.putShort(name.size.toShort()).putShort(extra.toShort()).put(name)
        if (e.zip64) b.putShort(1).putShort(16).putLong(e.size).putLong(e.size)
        raf.write(b.array())
        e.dataOffset = raf.filePointer
    }

    private fun writeCentral(raf: RandomAccessFile, entries: List<Entry>) {
        val cdStart = raf.filePointer
        for (e in entries) {
            val name = e.name.toByteArray(Charsets.UTF_8)
            val bigOff = e.headerOffset >= 0xFFFFFFFFL
            val z = ArrayList<Long>()
            if (e.zip64) { z += e.size; z += e.size }
            if (bigOff) z += e.headerOffset
            val extra = if (z.isEmpty()) 0 else 4 + 8 * z.size
            val b = ByteBuffer.allocate(46 + name.size + extra).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0x02014b50).putShort(0x031E).putShort((if (z.isEmpty()) 20 else 45).toShort())
                .putShort(0x0800).putShort(0).putShort(0).putShort(0x21).putInt(e.crc.toInt())
            if (e.zip64) b.putInt(-1).putInt(-1) else b.putInt(e.size.toInt()).putInt(e.size.toInt())
            b.putShort(name.size.toShort()).putShort(extra.toShort()).putShort(0).putShort(0).putShort(0)
                .putInt((0x81A4 shl 16)) // atrybuty: plik 0644
                .putInt(if (bigOff) -1 else e.headerOffset.toInt())
                .put(name)
            if (z.isNotEmpty()) { b.putShort(1).putShort((8 * z.size).toShort()); z.forEach { b.putLong(it) } }
            raf.write(b.array())
        }
        val cdEnd = raf.filePointer
        val cdSize = cdEnd - cdStart
        // ZIP64 EOCD + lokator + EOCD
        val z = ByteBuffer.allocate(56 + 20 + 22).order(ByteOrder.LITTLE_ENDIAN)
        z.putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
            .putLong(entries.size.toLong()).putLong(entries.size.toLong()).putLong(cdSize).putLong(cdStart)
        z.putInt(0x07064b50).putInt(0).putLong(cdEnd).putInt(1)
        z.putInt(0x06054b50).putShort(0).putShort(0).putShort(-1).putShort(-1).putInt(-1).putInt(-1).putShort(0)
        raf.write(z.array())
    }

    // ------------------------------------------------------------ skrypty

    /** Argumenty dd dla zapisu [size] bajtow z ZIP-a (data @ [src]) do celu od [dst]: czesc 1 MB + reszta 4 KB. */
    private fun ddArgs(src: Long, dst: Long, size: Long): String {
        val mb = size / MB
        val rem = size - mb * MB
        if (src % MB != 0L || dst % MB != 0L) throw IOException("Wyrownanie: $src $dst $size")
        return "${src / MB} ${dst / MB} $mb ${(src + mb * MB) / 4096} ${(dst + mb * MB) / 4096} ${(rem + 4095) / 4096}"
    }

    private fun recoveryScript(phys: List<Pair<String, Entry>>, meta: Entry, logical: List<Pair<String, Entry>>, plan: SuperBuilder.Plan, superSize: Long): String {
        val s = StringBuilder()
        s.append("""#!/sbin/sh
# Franken Flasher - hybrid super flasher (recovery)
OUTFD=/proc/self/fd/${'$'}2
ZIP="${'$'}3"
ui() { echo "ui_print ${'$'}1" >> ${'$'}OUTFD; echo "ui_print" >> ${'$'}OUTFD; }
BD=/dev/block/bootdevice/by-name
[ -d "${'$'}BD" ] || BD=/dev/block/by-name
ERR=0
# w CEL skipMB seekMB countMB skip4k seek4k count4k
w() {
  t="${'$'}BD/${'$'}1"
  [ -e "${'$'}t" ] || { ui "  - ${'$'}1: brak partycji / no partition"; return 0; }
  if [ "${'$'}4" != 0 ]; then dd if="${'$'}ZIP" of="${'$'}t" bs=1048576 skip=${'$'}2 seek=${'$'}3 count=${'$'}4 conv=notrunc 2>/dev/null || { ui "  ! ${'$'}1 BLAD/ERROR"; ERR=1; }; fi
  if [ "${'$'}7" != 0 ]; then dd if="${'$'}ZIP" of="${'$'}t" bs=4096 skip=${'$'}5 seek=${'$'}6 count=${'$'}7 conv=notrunc 2>/dev/null || { ui "  ! ${'$'}1 BLAD/ERROR"; ERR=1; }; fi
}
# p NAZWA ... - partycja fizyczna: oba sloty albo bez slotu
p() {
  n="${'$'}1"; shift
  if [ -e "${'$'}BD/${'$'}{n}_a" ]; then ui "  ${'$'}n (a+b)"; w "${'$'}{n}_a" "${'$'}@"; w "${'$'}{n}_b" "${'$'}@"
  else ui "  ${'$'}n"; w "${'$'}n" "${'$'}@"; fi
}
ui "**************************************"
ui " Franken Flasher - hybrid super flasher"
ui "**************************************"
for m in /system_root /system /vendor /product /odm /system_ext; do umount -l ${'$'}m 2>/dev/null; done
SZ=${'$'}(blockdev --getsize64 "${'$'}BD/super" 2>/dev/null)
[ -z "${'$'}SZ" ] && SZ=${'$'}(( ${'$'}(cat /sys/class/block/${'$'}(basename ${'$'}(readlink -f "${'$'}BD/super"))/size 2>/dev/null || echo 0) * 512 ))
if [ -n "${'$'}SZ" ] && [ "${'$'}SZ" != 0 ] && [ "${'$'}SZ" != "SUPERSIZE" ]; then
  ui "! Rozmiar super w telefonie (${'$'}SZ) != ZIP (SUPERSIZE) - inny model? PRZERWANO, nic nie zapisano"
  ui "! Phone super size differs from ZIP - wrong model? ABORTED, nothing written"
  exit 1
fi
ui "- Partycje fizyczne / physical partitions"
""".replace("SUPERSIZE", superSize.toString()))
        for ((n, e) in phys) s.append("p $n ${ddArgs(e.dataOffset, 0, e.size)}\n")
        s.append("ui \"- super: metadane / metadata\"\n")
        s.append("w super ${ddArgs(meta.dataOffset, 0, meta.size)}\n")
        s.append("ui \"- super: partycje logiczne / logical partitions (slot a)\"\n")
        for ((n, e) in logical) {
            val off = plan.offsets.getValue(n)
            s.append("ui \"  ${n}_a\"\nw super ${ddArgs(e.dataOffset, off, e.size)}\n")
        }
        s.append("""sync
if command -v bootctl >/dev/null 2>&1; then bootctl set-active-boot-slot 0 && ui "- Aktywny slot / active slot: a"; else ui "- Ustaw slot A w recovery (Reboot > Slot A) / set slot A"; fi
if [ ${'$'}ERR = 0 ]; then ui "- GOTOWE / DONE. Przy zmianie systemu zrob Format Data / Format Data when changing system"; exit 0; fi
ui "! Wystapily bledy / errors occurred"
exit 1
""")
        return s.toString()
    }

    private fun pcScriptBat(phys: List<Pair<String, File>>, logical: List<String>): String {
        val b = StringBuilder("@echo off\r\ncd /d \"%~dp0\"\r\nset FB=fastboot\r\nif exist platform-tools\\fastboot.exe set FB=platform-tools\\fastboot.exe\r\n")
        b.append("echo Hybrid super flasher - telefon w BOOTLOADER (fastboot)\r\n%FB% devices\r\npause\r\n")
        for ((g, f) in phys) b.append("%FB% flash --slot=all ${f.name.removeSuffix(".img")} \"$g\\${f.name}\" || goto err\r\n")
        b.append("%FB% flash super \"SUPER\\super_meta.img\" || goto err\r\necho Ostatni etap w fastbootd / last stage in fastbootd\r\n%FB% reboot fastboot\r\n")
        for (n in logical) b.append("%FB% flash ${n}_a \"SYSTEM\\$n.img\" || goto err\r\n")
        b.append("%FB% --set-active=a\r\nset /p W=Wyczyscic dane / wipe data? (t/n): \r\nif /i \"%W%\"==\"t\" %FB% -w\r\nif /i \"%W%\"==\"y\" %FB% -w\r\n")
        b.append("%FB% reboot\r\necho OK\r\npause\r\nexit /b 0\r\n:err\r\necho BLAD / ERROR\r\npause\r\nexit /b 1\r\n")
        return b.toString()
    }

    private fun pcScriptSh(phys: List<Pair<String, File>>, logical: List<String>): String {
        val b = StringBuilder("#!/bin/sh\nset -e\ncd \"$(dirname \"$0\")\"\nFB=fastboot\n[ -x platform-tools/fastboot ] && FB=platform-tools/fastboot\n")
        b.append("echo 'Hybrid super flasher - telefon w BOOTLOADER (fastboot)'\n\$FB devices\n")
        for ((g, f) in phys) b.append("\$FB flash --slot=all ${f.name.removeSuffix(".img")} \"$g/${f.name}\"\n")
        b.append("\$FB flash super SUPER/super_meta.img\necho 'Ostatni etap w fastbootd / last stage in fastbootd'\n\$FB reboot fastboot\n")
        for (n in logical) b.append("\$FB flash ${n}_a \"SYSTEM/$n.img\"\n")
        b.append("\$FB --set-active=a\nprintf 'Wipe data? (y/n): '; read W\n[ \"\$W\" = y ] || [ \"\$W\" = t ] && \$FB -w\n\$FB reboot\necho OK\n")
        return b.toString()
    }
}
