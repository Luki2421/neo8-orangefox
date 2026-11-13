package pl.luki.frankenflasher.ota

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class Extent(val start: Long, val num: Long)
class Op(val type: Int, val dataOffset: Long, val dataLength: Long, val dst: List<Extent>)
class PartUpdate(val name: String, val size: Long, val ops: List<Op>, val hash: ByteArray?)
class DynGroup(val name: String, val size: Long, val partitions: List<String>)

class PayloadInfo(val blockSize: Int, val parts: List<PartUpdate>, val dataStart: Long, val dynamic: Set<String>, val groups: List<DynGroup> = emptyList())

object OpType {
    const val REPLACE = 0
    const val REPLACE_BZ = 1
    const val ZERO = 6
    const val DISCARD = 7
    const val REPLACE_XZ = 8
    const val ZSTD = 14
}

/** Strumien z fragmentu pliku (odczyty pozycyjne - bezpieczne dla wielu watkow). */
class RegionInputStream(private val ch: FileChannel, private var pos: Long, len: Long) : InputStream() {
    private var left = len
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = ch.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), left).toInt()), pos)
        if (n <= 0) return -1
        pos += n; left -= n
        return n
    }
}

object PayloadParser {
    fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val bb = ByteBuffer.allocate(len)
        var p = pos
        while (bb.hasRemaining()) { val n = ch.read(bb, p); if (n < 0) throw EOFException(); p += n }
        return bb.array()
    }

    /** [base] = przesuniecie payload.bin w pliku (0 dla samego payload.bin, >0 wewnatrz ZIP). */
    fun parse(ch: FileChannel, base: Long): PayloadInfo {
        val h = ByteBuffer.wrap(readAt(ch, base, 24)).order(ByteOrder.BIG_ENDIAN)
        if (String(h.array(), 0, 4, Charsets.US_ASCII) != "CrAU") throw IllegalArgumentException("To nie jest payload.bin (brak CrAU)")
        val ver = h.getLong(4)
        val manSize = h.getLong(12)
        val sigSize = if (ver >= 2) h.getInt(20).toLong() and 0xFFFFFFFFL else 0L
        val hdr = if (ver >= 2) 24L else 20L
        val man = Pb(readAt(ch, base + hdr, manSize.toInt()))
        var blockSize = 4096
        val parts = ArrayList<PartUpdate>()
        val dynamic = HashSet<String>()
        val groups = ArrayList<DynGroup>()
        while (man.more()) {
            val (f, wt) = man.tag()
            when {
                f == 3 && wt == 0 -> blockSize = man.varint().toInt()
                f == 13 && wt == 2 -> parts += parsePart(man.sub())
                f == 15 && wt == 2 -> parseDynamic(man.sub(), dynamic, groups)
                else -> man.skip(wt)
            }
        }
        return PayloadInfo(blockSize, parts, base + hdr + manSize + sigSize, dynamic, groups)
    }

    private fun parsePart(m: Pb): PartUpdate {
        var name = ""
        var size = 0L
        var hash: ByteArray? = null
        val ops = ArrayList<Op>()
        while (m.more()) {
            val (f, wt) = m.tag()
            when {
                f == 1 && wt == 2 -> name = m.string()
                f == 7 && wt == 2 -> {
                    val pi = m.sub()
                    while (pi.more()) {
                        val (g, w) = pi.tag()
                        when {
                            g == 1 && w == 0 -> size = pi.varint()
                            g == 2 && w == 2 -> hash = pi.bytes()
                            else -> pi.skip(w)
                        }
                    }
                }
                f == 8 && wt == 2 -> ops += parseOp(m.sub())
                else -> m.skip(wt)
            }
        }
        return PartUpdate(name, size, ops, hash)
    }

    private fun parseOp(m: Pb): Op {
        var type = 0; var off = 0L; var len = 0L
        val dst = ArrayList<Extent>()
        while (m.more()) {
            val (f, wt) = m.tag()
            when {
                f == 1 && wt == 0 -> type = m.varint().toInt()
                f == 2 && wt == 0 -> off = m.varint()
                f == 3 && wt == 0 -> len = m.varint()
                f == 6 && wt == 2 -> {
                    val e = m.sub(); var s = 0L; var n = 0L
                    while (e.more()) { val (g, w) = e.tag(); when { g == 1 && w == 0 -> s = e.varint(); g == 2 && w == 0 -> n = e.varint(); else -> e.skip(w) } }
                    dst += Extent(s, n)
                }
                else -> m.skip(wt)
            }
        }
        return Op(type, off, len, dst)
    }

    private fun parseDynamic(m: Pb, out: MutableSet<String>, groups: MutableList<DynGroup>) {
        while (m.more()) {
            val (f, wt) = m.tag()
            if (f == 1 && wt == 2) {
                val g = m.sub()
                var name = ""; var size = 0L; val names = ArrayList<String>()
                while (g.more()) {
                    val (h, w) = g.tag()
                    when {
                        h == 1 && w == 2 -> name = g.string()
                        h == 2 && w == 0 -> size = g.varint()
                        h == 3 && w == 2 -> { val n = g.string(); out += n; names += n }
                        else -> g.skip(w)
                    }
                }
                groups += DynGroup(name, size, names)
            } else m.skip(wt)
        }
    }
}

/** Znajduje payload.bin w ZIP (musi byc STORED); obsluguje ZIP64. Zwraca przesuniecie danych lub null. */
object ZipLocator {
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    fun findStored(ch: FileChannel, entry: String): Long? {
        val size = ch.size()
        val tailLen = minOf(size, 65_557L).toInt()
        val tail = PayloadParser.readAt(ch, size - tailLen, tailLen)
        var e = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) { e = i; break }
        }
        if (e < 0) return null
        val eb = le(tail)
        var entries = (eb.getShort(e + 10).toInt() and 0xFFFF).toLong()
        var cdSize = eb.getInt(e + 12).toLong() and 0xFFFFFFFFL
        var cdOff = eb.getInt(e + 16).toLong() and 0xFFFFFFFFL
        if (cdOff == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || entries == 0xFFFFL) {
            val locPos = size - tailLen + e - 20
            val loc = le(PayloadParser.readAt(ch, locPos, 20))
            if (loc.getInt(0) == 0x07064b50) {
                val z = le(PayloadParser.readAt(ch, loc.getLong(8), 56))
                if (z.getInt(0) == 0x06064b50) { entries = z.getLong(32); cdSize = z.getLong(40); cdOff = z.getLong(48) }
            }
        }
        val cd = le(PayloadParser.readAt(ch, cdOff, cdSize.toInt()))
        var p = 0
        for (k in 0 until entries) {
            if (cd.getInt(p) != 0x02014b50) break
            val method = cd.getShort(p + 10).toInt() and 0xFFFF
            var comp = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL
            var uncomp = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commLen = cd.getShort(p + 32).toInt() and 0xFFFF
            var lho = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val name = String(cd.array(), p + 46, nameLen, Charsets.UTF_8)
            var x = p + 46 + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd) {
                val id = cd.getShort(x).toInt() and 0xFFFF
                val sz = cd.getShort(x + 2).toInt() and 0xFFFF
                if (id == 1) {
                    var q = x + 4
                    if (uncomp == 0xFFFFFFFFL) { uncomp = cd.getLong(q); q += 8 }
                    if (comp == 0xFFFFFFFFL) { comp = cd.getLong(q); q += 8 }
                    if (lho == 0xFFFFFFFFL) { lho = cd.getLong(q) }
                }
                x += 4 + sz
            }
            if (name == entry) {
                if (method != 0) throw IllegalArgumentException("$entry w ZIP jest skompresowany - rozpakuj ZIP i wybierz sam payload.bin")
                val lh = le(PayloadParser.readAt(ch, lho, 30))
                if (lh.getInt(0) != 0x04034b50) throw IllegalArgumentException("Uszkodzony naglowek ZIP")
                return lho + 30 + (lh.getShort(26).toInt() and 0xFFFF) + (lh.getShort(28).toInt() and 0xFFFF)
            }
            p += 46 + nameLen + extraLen + commLen
        }
        return null
    }
}
