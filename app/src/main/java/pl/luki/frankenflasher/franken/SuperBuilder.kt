package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Odpowiednik "lpmake --metadata-size 65536 --metadata-slots 3 --virtual-ab --sparse":
 * buduje super.img (format liblp v10.2) z obrazow partycji logicznych - od razu jako sparse,
 * bez tworzenia pelnego pliku wielkosci partycji super.
 */
class SuperBuilder(private val log: (String) -> Unit, private val progress: (Long, Long) -> Unit = { _, _ -> }) {

    class Group(val name: String, val maxSize: Long)
    class Part(val name: String, val image: File?, val size: Long, val group: String)

    companion object {
        const val SECTOR = 512L
        const val BLOCK = 4096
        const val RESERVED = 4096L
        const val GEOMETRY_SIZE = 4096L
        const val METADATA_MAX = 65536
        const val SLOTS = 3
        const val ALIGN = 1L shl 20
        private const val GEO_MAGIC = 0x616c4467
        private const val HDR_MAGIC = 0x414C5030
        private const val ATTR_READONLY = 1
        private const val HDR_FLAG_VIRTUAL_AB = 1
        private const val SPARSE_MAGIC = 0xED26FF3A.toInt()

        fun alignUp(v: Long, a: Long) = (v + a - 1) / a * a
    }

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun name36(b: ByteBuffer, s: String) {
        val a = s.toByteArray(Charsets.US_ASCII)
        if (a.size > 35) throw IOException("Za dluga nazwa: $s")
        b.put(a); repeat(36 - a.size) { b.put(0) }
    }
    private fun sha256(a: ByteArray, off: Int = 0, len: Int = a.size): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(a, off, len) }.digest()

    /** Ulozenie partycji: (partycja -> pierwszy sektor). */
    private class Layout(val firstLogicalSector: Long, val starts: Map<Part, Long>)

    private fun layout(parts: List<Part>, superSize: Long): Layout {
        val metaEnd = RESERVED + GEOMETRY_SIZE * 2 + METADATA_MAX.toLong() * SLOTS * 2
        val first = alignUp(metaEnd, ALIGN)
        var pos = first
        val starts = LinkedHashMap<Part, Long>()
        for (p in parts) {
            if (p.size == 0L) continue
            pos = alignUp(pos, ALIGN)
            starts[p] = pos / SECTOR
            pos += alignUp(p.size, BLOCK.toLong())
        }
        if (pos > superSize) throw IOException(tr(
            "Partycje (${pos / (1 shl 20)} MB) nie mieszcza sie w super (${superSize / (1 shl 20)} MB)",
            "Partitions (${pos / (1 shl 20)} MB) do not fit in super (${superSize / (1 shl 20)} MB)"))
        return Layout(first / SECTOR, starts)
    }

    private fun geometry(): ByteArray {
        val b = le(GEOMETRY_SIZE.toInt())
        b.putInt(GEO_MAGIC).putInt(52)
        b.position(40)
        b.putInt(METADATA_MAX).putInt(SLOTS).putInt(BLOCK)
        val a = b.array()
        System.arraycopy(sha256(a, 0, 52), 0, a, 8, 32)
        return a
    }

    private fun metadata(parts: List<Part>, groups: List<Group>, superSize: Long, lay: Layout): ByteArray {
        val allGroups = listOf(Group("default", 0)) + groups
        // tabele
        val pt = le(52 * parts.size); val et = le(24 * lay.starts.size)
        var extIdx = 0
        for (p in parts) {
            name36(pt, p.name)
            pt.putInt(ATTR_READONLY)
            val start = lay.starts[p]
            if (start != null) {
                pt.putInt(extIdx).putInt(1)
                et.putLong(alignUp(p.size, BLOCK.toLong()) / SECTOR).putInt(0).putLong(start).putInt(0)
                extIdx++
            } else pt.putInt(0).putInt(0)
            val gi = allGroups.indexOfFirst { it.name == p.group }
            if (gi < 0) throw IOException("Brak grupy ${p.group}")
            pt.putInt(gi)
        }
        val gt = le(48 * allGroups.size)
        for (g in allGroups) { name36(gt, g.name); gt.putInt(0).putLong(g.maxSize) }
        val bt = le(64)
        bt.putLong(lay.firstLogicalSector).putInt(ALIGN.toInt()).putInt(0).putLong(superSize)
        name36(bt, "super"); bt.putInt(0)
        val tables = pt.array() + et.array() + gt.array() + bt.array()
        // naglowek v10.2 (256 B)
        val h = le(256)
        h.putInt(HDR_MAGIC).putShort(10).putShort(2).putInt(256)
        h.position(44)
        h.putInt(tables.size)
        h.position(80)
        var off = 0
        fun desc(n: Int, sz: Int) { h.putInt(off).putInt(n).putInt(sz); off += n * sz }
        desc(parts.size, 52); desc(lay.starts.size, 24); desc(allGroups.size, 48); desc(1, 64)
        h.putInt(HDR_FLAG_VIRTUAL_AB)
        val ha = h.array()
        System.arraycopy(sha256(tables), 0, ha, 48, 32)
        System.arraycopy(sha256(ha), 0, ha, 12, 32)
        val m = ha + tables
        if (m.size > METADATA_MAX) throw IOException("Metadane za duze: ${m.size}")
        return m
    }

    /**
     * Buduje sparse super.img. [parts] - partycje slotu A z obrazami, slot B tworzony pusty.
     * Zwraca liczbe zapisanych bajtow.
     */
    fun build(images: Map<String, File>, groupName: String, groupMax: Long, superSize: Long, out: File): Long {
        if (superSize % BLOCK != 0L) throw IOException("Rozmiar super nie jest wielokrotnoscia 4096")
        val names = images.keys.sorted()
        val ga = "${groupName}_a"; val gb = "${groupName}_b"
        val parts = names.map { Part("${it}_a", images.getValue(it), images.getValue(it).length(), ga) } +
            names.map { Part("${it}_b", null, 0L, gb) }
        val groups = listOf(Group(ga, groupMax), Group(gb, groupMax))
        val used = parts.sumOf { it.size }
        if (used > groupMax) throw IOException(tr(
            "Obrazy (${used / (1 shl 20)} MB) przekraczaja grupe $ga (${groupMax / (1 shl 20)} MB)",
            "Images (${used / (1 shl 20)} MB) exceed group $ga (${groupMax / (1 shl 20)} MB)"))
        val lay = layout(parts, superSize)
        val meta = metadata(parts, groups, superSize, lay)
        val geo = geometry()

        // blok poczatkowy: reserved + 2x geometria + 3 sloty glowne + 3 zapasowe
        val head = ByteArray((lay.firstLogicalSector * SECTOR).toInt())
        System.arraycopy(geo, 0, head, RESERVED.toInt(), geo.size)
        System.arraycopy(geo, 0, head, (RESERVED + GEOMETRY_SIZE).toInt(), geo.size)
        for (s in 0 until SLOTS) {
            val prim = RESERVED + GEOMETRY_SIZE * 2 + METADATA_MAX.toLong() * s
            val back = RESERVED + GEOMETRY_SIZE * 2 + METADATA_MAX.toLong() * SLOTS + METADATA_MAX.toLong() * s
            System.arraycopy(meta, 0, head, prim.toInt(), meta.size)
            System.arraycopy(meta, 0, head, back.toInt(), meta.size)
        }

        // plan chunkow sparse
        class Chunk(val raw: File?, val bytes: ByteArray?, val blocks: Long, val rawOffset: Long = 0)
        val chunks = ArrayList<Chunk>()
        chunks += Chunk(null, head, head.size / BLOCK.toLong())
        var cur = head.size.toLong()
        for ((p, startSector) in lay.starts) {
            val start = startSector * SECTOR
            if (start > cur) chunks += Chunk(null, null, (start - cur) / BLOCK)
            val blocks = alignUp(p.size, BLOCK.toLong()) / BLOCK
            val maxBlocks = (512L shl 20) / BLOCK // chunk RAW <= 512 MB (pole total_sz to u32)
            var b = 0L
            while (b < blocks) { val n = minOf(maxBlocks, blocks - b); chunks += Chunk(p.image, null, n, b * BLOCK); b += n }
            cur = start + blocks * BLOCK
        }
        if (superSize > cur) chunks += Chunk(null, null, (superSize - cur) / BLOCK)

        val total = images.values.sumOf { it.length() }
        var done = 0L
        log(tr("super: ${names.size} partycji, ${used / (1 shl 20)} MB, super ${superSize / (1 shl 20)} MB, grupa $groupName ${groupMax / (1 shl 20)} MB",
            "super: ${names.size} partitions, ${used / (1 shl 20)} MB, super ${superSize / (1 shl 20)} MB, group $groupName ${groupMax / (1 shl 20)} MB"))
        out.parentFile?.mkdirs()
        BufferedOutputStream(out.outputStream(), 4 shl 20).use { o ->
            val fh = le(28)
            fh.putInt(SPARSE_MAGIC).putShort(1).putShort(0).putShort(28).putShort(12)
                .putInt(BLOCK).putInt((superSize / BLOCK).toInt()).putInt(chunks.size).putInt(0)
            o.write(fh.array())
            val buf = ByteArray(4 shl 20)
            for (c in chunks) {
                val ch = le(12)
                when {
                    c.bytes != null -> {
                        ch.putShort(0xCAC1.toShort()).putShort(0).putInt(c.blocks.toInt()).putInt((12 + c.blocks * BLOCK).toInt())
                        o.write(ch.array()); o.write(c.bytes)
                    }
                    c.raw != null -> {
                        ch.putShort(0xCAC1.toShort()).putShort(0).putInt(c.blocks.toInt()).putInt((12 + c.blocks * BLOCK).toInt())
                        o.write(ch.array())
                        var left = c.blocks * BLOCK
                        java.io.RandomAccessFile(c.raw, "r").use { raf ->
                            raf.seek(c.rawOffset)
                            val i = java.io.FileInputStream(raf.fd)
                            while (left > 0) {
                                val want = minOf(left, buf.size.toLong()).toInt()
                                var got = 0
                                while (got < want) { val r = i.read(buf, got, want - got); if (r < 0) break; got += r }
                                if (got < want) java.util.Arrays.fill(buf, got, want, 0)
                                o.write(buf, 0, want)
                                left -= want; done += got.toLong()
                                progress(done, total)
                            }
                        }
                    }
                    else -> {
                        ch.putShort(0xCAC3.toShort()).putShort(0).putInt(c.blocks.toInt()).putInt(12)
                        o.write(ch.array())
                    }
                }
            }
        }
        return out.length()
    }
}
