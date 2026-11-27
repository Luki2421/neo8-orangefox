package pl.luki.frankenflasher.franken

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Odczyt plikow z obrazu ext4 (tylko odczyt): ROM-y AOSP (LineageOS, DerpFest...) czesto maja system,
 * system_ext i product w ext4, a nie w EROFS. Obsluga: drzewa extentow (dowolna glebokosc), extenty
 * niezainicjowane (zera), katalogi liniowe i htree (bloki czytane liniowo), dane inline w i_block,
 * symlinki w sciezce (wzgledne i bezwzgledne wzgledem korzenia obrazu).
 */
class Ext4Reader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val blockSize: Int
    private val inodesPerGroup: Int
    private val inodeSize: Int
    private val descSize: Int
    private val is64: Boolean
    private val firstDataBlock: Long

    init {
        val sb = read(1024, 1024)
        if ((sb.getShort(0x38).toInt() and 0xFFFF) != 0xEF53) throw IOException("To nie jest obraz ext4")
        firstDataBlock = sb.getInt(0x14).toLong() and 0xFFFFFFFFL
        blockSize = 1024 shl sb.getInt(0x18)
        inodesPerGroup = sb.getInt(0x28)
        inodeSize = if (sb.getInt(0x4C) == 0) 128 else sb.getShort(0x58).toInt() and 0xFFFF
        val incompat = sb.getInt(0x60)
        is64 = incompat and 0x80 != 0
        descSize = if (is64) (sb.getShort(0xFE).toInt() and 0xFFFF).coerceAtLeast(32) else 32
    }

    companion object {
        fun isExt4(f: File): Boolean = try {
            RandomAccessFile(f, "r").use { r ->
                if (r.length() < 2048) return false
                r.seek(1024 + 0x38L); (r.read() or (r.read() shl 8)) == 0xEF53
            }
        } catch (_: Exception) { false }
    }

    private fun read(off: Long, len: Int): ByteBuffer {
        val b = ByteArray(len)
        raf.seek(off); raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    private class Inode(val mode: Int, val size: Long, val flags: Int, val iblock: ByteArray, val inlineMore: ByteArray)

    private fun inode(n: Long): Inode {
        val g = (n - 1) / inodesPerGroup
        val idx = (n - 1) % inodesPerGroup
        val gd = read((firstDataBlock + 1) * blockSize + g * descSize, descSize)
        var table = gd.getInt(0x08).toLong() and 0xFFFFFFFFL
        if (is64 && descSize >= 64) table = table or ((gd.getInt(0x28).toLong() and 0xFFFFFFFFL) shl 32)
        val b = read(table * blockSize + idx * inodeSize, inodeSize)
        val size = (b.getInt(0x04).toLong() and 0xFFFFFFFFL) or ((b.getInt(0x6C).toLong() and 0xFFFFFFFFL) shl 32)
        val ib = ByteArray(60); b.position(0x28); b.get(ib)
        val flags = b.getInt(0x20)
        return Inode(b.getShort(0).toInt() and 0xFFFF, size, flags, ib, if (flags and 0x10000000 != 0) systemData(b) else ByteArray(0))
    }

    /** Dane inline ponad 60 B: atrybut "system.data" w przestrzeni xattr wewnatrz i-wezla. */
    private fun systemData(b: ByteBuffer): ByteArray {
        if (inodeSize <= 128) return ByteArray(0)
        val start = 128 + (b.getShort(0x80).toInt() and 0xFFFF)
        if (start + 4 > inodeSize || b.getInt(start) != 0xEA020000.toInt()) return ByteArray(0)
        val first = start + 4
        var p = first
        while (p + 16 <= inodeSize) {
            val nameLen = b.get(p).toInt() and 0xFF
            val nameIdx = b.get(p + 1).toInt() and 0xFF
            if (nameLen == 0 && nameIdx == 0) break
            val off = b.getShort(p + 2).toInt() and 0xFFFF
            val vsize = b.getInt(p + 8)
            val name = String(b.array(), p + 16, nameLen)
            if (nameIdx == 7 && name == "data" && first + off + vsize <= inodeSize) return b.array().copyOfRange(first + off, first + off + vsize)
            p += (16 + nameLen + 3) and 3.inv()
        }
        return ByteArray(0)
    }

    /** Lista (blok logiczny, blok fizyczny, dlugosc, niezainicjowany) z drzewa extentow. */
    private fun extents(node: ByteBuffer, out: MutableList<LongArray>) {
        if ((node.getShort(0).toInt() and 0xFFFF) != 0xF30A) throw IOException("ext4: zly naglowek extentow")
        val entries = node.getShort(2).toInt() and 0xFFFF
        val depth = node.getShort(6).toInt() and 0xFFFF
        for (i in 0 until entries) {
            val e = 12 + i * 12
            if (depth == 0) {
                var len = node.getShort(e + 4).toInt() and 0xFFFF
                val uninit = len > 32768
                if (uninit) len -= 32768
                val start = ((node.getShort(e + 6).toLong() and 0xFFFF) shl 32) or (node.getInt(e + 8).toLong() and 0xFFFFFFFFL)
                out += longArrayOf(node.getInt(e).toLong() and 0xFFFFFFFFL, start, len.toLong(), if (uninit) 1 else 0)
            } else {
                val leaf = (node.getInt(e + 4).toLong() and 0xFFFFFFFFL) or ((node.getShort(e + 8).toLong() and 0xFFFF) shl 32)
                extents(read(leaf * blockSize, blockSize), out)
            }
        }
    }

    private fun data(ino: Inode): ByteArray {
        if (ino.size > Int.MAX_VALUE) throw IOException("ext4: plik za duzy")
        val out = ByteArray(ino.size.toInt())
        if (ino.flags and 0x10000000 != 0) { // INLINE_DATA: 60 B w i_block + reszta w xattr system.data
            val all = ino.iblock + ino.inlineMore
            System.arraycopy(all, 0, out, 0, minOf(out.size, all.size)); return out
        }
        if (ino.flags and 0x80000 == 0) {
            if ((ino.mode and 0xF000) == 0xA000 && ino.size < 60) { System.arraycopy(ino.iblock, 0, out, 0, out.size); return out } // szybki symlink
            throw IOException("ext4: plik bez extentow (stary format) - nieobslugiwany")
        }
        val ext = ArrayList<LongArray>()
        extents(ByteBuffer.wrap(ino.iblock).order(ByteOrder.LITTLE_ENDIAN), ext)
        for ((lblk, pblk, len, uninit) in ext.map { listOf(it[0], it[1], it[2], it[3]) }) {
            if (uninit == 1L) continue
            for (k in 0 until len) {
                val pos = (lblk + k) * blockSize
                if (pos >= out.size) break
                val n = minOf(blockSize.toLong(), out.size - pos).toInt()
                raf.seek((pblk + k) * blockSize); raf.readFully(out, pos.toInt(), n)
            }
        }
        return out
    }

    /** Wpisy katalogu: nazwa -> numer i-wezla. */
    private fun dir(ino: Inode): Map<String, Long> {
        if (ino.flags and 0x10000000 != 0) { // katalog inline: 4 B rodzica, potem wpisy (bez "." i "..")
            val out = LinkedHashMap<String, Long>()
            for (part in listOf(ino.iblock.copyOfRange(4, 60), ino.inlineMore)) parseDir(part, out, part.size)
            return out
        }
        val d = data(ino)
        val out = LinkedHashMap<String, Long>()
        parseDir(d, out, blockSize)
        return out
    }

    private fun parseDir(d: ByteArray, out: MutableMap<String, Long>, unit: Int) {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p + 8 <= d.size) {
            val n = b.getInt(p).toLong() and 0xFFFFFFFFL
            val rec = b.getShort(p + 4).toInt() and 0xFFFF
            val nameLen = b.get(p + 6).toInt() and 0xFF
            if (rec < 8) { if (unit <= 0) break; p = (p / unit + 1) * unit; continue }
            if (n != 0L && nameLen > 0 && p + 8 + nameLen <= d.size) out[String(d, p + 8, nameLen)] = n
            p += rec
        }
    }

    /** Zawartosc pliku [path] (wzgledem korzenia obrazu) albo null, gdy go nie ma. */
    fun cat(path: String): ByteArray? = resolve(path)?.let { data(it) }

    /**
     * Polozenie w obrazie bajtu [off] pliku [path] (do poprawek tej samej dlugosci w miejscu: dane
     * plikow ext4 nie maja sum kontrolnych). null: brak pliku, dane inline albo extent niezainicjowany.
     */
    fun physicalOffset(path: String, off: Long): Long? {
        val ino = resolve(path) ?: return null
        if (off < 0 || off >= ino.size || ino.flags and 0x10000000 != 0 || ino.flags and 0x80000 == 0) return null
        val ext = ArrayList<LongArray>()
        extents(ByteBuffer.wrap(ino.iblock).order(ByteOrder.LITTLE_ENDIAN), ext)
        val lblk = off / blockSize
        val e = ext.firstOrNull { lblk >= it[0] && lblk < it[0] + it[2] } ?: return null
        if (e[3] == 1L) return null
        return (e[1] + (lblk - e[0])) * blockSize + off % blockSize
    }

    /** I-wezel zwyklego pliku [path] (symlinki po drodze rozwiazywane) albo null. */
    private fun resolve(path: String): Inode? {
        var parts = path.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
        var cur = inode(2)
        var i = 0
        var hops = 0
        val stack = ArrayList<Inode>()
        while (i < parts.size) {
            if ((cur.mode and 0xF000) != 0x4000) return null
            val name = parts[i]
            if (name == "..") { cur = if (stack.isEmpty()) inode(2) else stack.removeAt(stack.size - 1); i++; continue }
            val n = dir(cur)[name] ?: return null
            val next = inode(n)
            if ((next.mode and 0xF000) == 0xA000) { // symlink
                if (++hops > 16) return null
                val target = String(data(next))
                val rest = parts.drop(i + 1)
                parts = if (target.startsWith("/")) target.trim('/').split('/') + rest else parts.take(i) + target.split('/') + rest
                if (target.startsWith("/")) { cur = inode(2); stack.clear(); i = 0 }
                parts = parts.filter { it.isNotEmpty() && it != "." }
                continue
            }
            stack += cur
            cur = next
            i++
        }
        return if ((cur.mode and 0xF000) == 0x8000) cur else null
    }

    /** Nazwy w katalogu [path] (null gdy brak). */
    fun list(path: String): List<String>? {
        var cur = inode(2)
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            if ((cur.mode and 0xF000) != 0x4000) return null
            cur = inode(dir(cur)[part] ?: return null)
        }
        return if ((cur.mode and 0xF000) == 0x4000) dir(cur).keys.filter { it != "." && it != ".." } else null
    }

    override fun close() = raf.close()
}
