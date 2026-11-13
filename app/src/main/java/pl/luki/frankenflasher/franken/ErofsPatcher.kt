package pl.luki.frankenflasher.franken

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edycja obrazu EROFS "w miejscu" bez przepakowania:
 * nowe dane, nowe i-wezly oraz przebudowane katalogi sa dopisywane na koncu obrazu,
 * a istniejace i-wezly przepinane na nie. Wszystko inne (etykiety SELinux, kompresja,
 * uprawnienia) zostaje bajt w bajt jak w oryginale.
 */
class ErofsPatcher(file: File, readOnly: Boolean = false) : AutoCloseable {

    class Xattr(val index: Int, val name: ByteArray, val value: ByteArray) {
        val key get() = "$index:" + String(name, Charsets.ISO_8859_1)
        override fun toString() = (PREFIX[index] ?: "[$index]") + String(name) + "=" + String(value).trimEnd('\u0000')
        companion object { val PREFIX = mapOf(1 to "user.", 2 to "system.posix_acl_access", 3 to "system.posix_acl_default", 4 to "trusted.", 6 to "security.") }
    }

    class Inode(
        val nid: Long, val offset: Long, val extended: Boolean, val layout: Int,
        val xattrIcount: Int, val mode: Int, val nlink: Int, val size: Long, val rawBlk: Long,
        val ino: Long, val uid: Int, val gid: Int, val mtime: Long, val mtimeNs: Int,
    ) {
        val inodeSize get() = if (extended) 64 else 32
        val xattrSize get() = if (xattrIcount == 0) 0 else 12 + 4 * (xattrIcount - 1)
        val isDir get() = (mode and 0xF000) == 0x4000
        val isReg get() = (mode and 0xF000) == 0x8000
        val isLink get() = (mode and 0xF000) == 0xA000
    }

    class Dirent(val name: String, val nid: Long, val type: Int)

    private val raf = RandomAccessFile(file, if (readOnly) "r" else "rw")
    private val sb = ByteArray(128)
    val blkBits: Int
    val blk: Int
    private val metaBlk: Long
    private val xattrBlk: Long
    val rootNid: Long
    private var inos: Long
    private val featureCompat: Int
    private val featureIncompat: Int
    private val dirBlk: Int

    init {
        raf.seek(1024); raf.readFully(sb)
        val b = le(sb)
        if (b.getInt(0) != MAGIC) throw IOException("To nie jest obraz EROFS")
        featureCompat = b.getInt(8)
        blkBits = sb[12].toInt() and 0xFF
        blk = 1 shl blkBits
        rootNid = (b.getShort(14).toLong() and 0xFFFF)
        inos = b.getLong(16)
        metaBlk = b.getInt(40).toLong() and 0xFFFFFFFFL
        xattrBlk = b.getInt(44).toLong() and 0xFFFFFFFFL
        featureIncompat = b.getInt(80)
        val dbits = sb[90].toInt() and 0xFF
        dirBlk = blk shl dbits
        if (sb[13].toInt() != 0) throw IOException("Nieobslugiwane: sb_extslots")
    }

    // ------------------------------------------------------------ odczyt

    private fun read(off: Long, len: Int): ByteArray {
        val a = ByteArray(len)
        raf.seek(off); raf.readFully(a)
        return a
    }

    private fun nidOffset(nid: Long) = metaBlk * blk + nid * 32

    fun inode(nid: Long): Inode {
        val off = nidOffset(nid)
        val h = le(read(off, minOf(64L, raf.length() - off).toInt()))
        val fmt = h.getShort(0).toInt() and 0xFFFF
        val ext = (fmt and 1) == 1
        val layout = (fmt shr 1) and 7
        val icount = h.getShort(2).toInt() and 0xFFFF
        val mode = h.getShort(4).toInt() and 0xFFFF
        return if (!ext) Inode(
            nid, off, false, layout, icount, mode,
            nlink = h.getShort(6).toInt() and 0xFFFF,
            size = h.getInt(8).toLong() and 0xFFFFFFFFL,
            rawBlk = h.getInt(16).toLong() and 0xFFFFFFFFL,
            ino = h.getInt(20).toLong() and 0xFFFFFFFFL,
            uid = h.getShort(24).toInt() and 0xFFFF, gid = h.getShort(26).toInt() and 0xFFFF,
            mtime = 0, mtimeNs = 0,
        ) else Inode(
            nid, off, true, layout, icount, mode,
            nlink = h.getInt(44),
            size = h.getLong(8),
            rawBlk = h.getInt(16).toLong() and 0xFFFFFFFFL,
            ino = h.getInt(20).toLong() and 0xFFFFFFFFL,
            uid = h.getInt(24), gid = h.getInt(28),
            mtime = h.getLong(32), mtimeNs = h.getInt(40),
        )
    }

    /** Dane i-wezla w ukladzie plaskim (PLAIN / INLINE) - katalogi, symlinki, nieskompresowane pliki. */
    fun readFlat(ino: Inode): ByteArray {
        if (ino.size > Int.MAX_VALUE) throw IOException("Za duzy")
        val size = ino.size.toInt()
        return when (ino.layout) {
            LAYOUT_PLAIN -> read(ino.rawBlk * blk, size)
            LAYOUT_INLINE -> {
                val full = (size / blk) * blk
                val out = ByteArray(size)
                if (full > 0) System.arraycopy(read(ino.rawBlk * blk, full), 0, out, 0, full)
                val tail = size - full
                if (tail > 0) System.arraycopy(read(ino.offset + ino.inodeSize + ino.xattrSize, tail), 0, out, full, tail)
                out
            }
            else -> throw IOException("i-wezel ${ino.nid}: uklad ${ino.layout} (skompresowany) - uzyj fsck.erofs --extract")
        }
    }

    fun readDir(nid: Long): List<Dirent> {
        val ino = inode(nid)
        if (!ino.isDir) throw IOException("nid $nid to nie katalog")
        val data = readFlat(ino)
        val out = ArrayList<Dirent>()
        var base = 0
        while (base < data.size) {
            val max = minOf(dirBlk, data.size - base)
            val first = le(data).getShort(base + 8).toInt() and 0xFFFF
            val n = first / 12
            for (i in 0 until n) {
                val e = base + i * 12
                val dnid = le(data).getLong(e)
                val noff = le(data).getShort(e + 8).toInt() and 0xFFFF
                val type = data[e + 10].toInt() and 0xFF
                val end = if (i + 1 < n) le(data).getShort(e + 12 + 8).toInt() and 0xFFFF else max
                var len = end - noff
                if (i + 1 == n) { var k = 0; while (k < len && data[base + noff + k] != 0.toByte()) k++; len = k }
                out += Dirent(String(data, base + noff, len, Charsets.UTF_8), dnid, type)
            }
            base += dirBlk
        }
        return out
    }

    /** Wpisy katalogu (bez . i ..) albo null, gdy sciezka nie istnieje / nie jest katalogiem. */
    fun list(path: String): List<Dirent>? {
        val nid = lookup(path) ?: return null
        if (!inode(nid).isDir) return null
        return readDir(nid).filter { it.name != "." && it.name != ".." }
    }

    /** Wzor atrybutow (tryb, uid, gid, xattry) i-wezla. */
    fun attrsOf(nid: Long): Node {
        val i = inode(nid)
        val x = xattrs(i)
        return if (i.isDir) NewDir(i.mode, i.uid, i.gid, x) else NewFile(i.mode, i.uid, i.gid, x, ByteArray(0))
    }

    fun lookup(path: String): Long? {
        var nid = rootNid
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            nid = readDir(nid).firstOrNull { it.name == part }?.nid ?: return null
        }
        return nid
    }

    private fun parseEntries(buf: ByteBuffer, end: Int, out: MutableList<Xattr>, limit: Int = Int.MAX_VALUE) {
        var n = 0
        while (buf.position() + 4 <= end && n < limit) {
            val nameLen = buf.get().toInt() and 0xFF
            val idx = buf.get().toInt() and 0xFF
            val vlen = buf.getShort().toInt() and 0xFFFF
            if (idx and 0x80 != 0) throw IOException("Nieobslugiwane: dlugie prefiksy xattr")
            val name = ByteArray(nameLen).also { buf.get(it) }
            val value = ByteArray(vlen).also { buf.get(it) }
            out += Xattr(idx, name, value)
            val pad = (4 - ((4 + nameLen + vlen) and 3)) and 3
            buf.position(buf.position() + pad)
            n++
        }
    }

    fun xattrs(ino: Inode): List<Xattr> {
        if (ino.xattrIcount == 0) return emptyList()
        val raw = read(ino.offset + ino.inodeSize, ino.xattrSize)
        val b = le(raw)
        val shared = raw[4].toInt() and 0xFF
        val out = ArrayList<Xattr>()
        for (i in 0 until shared) {
            val id = b.getInt(12 + 4 * i).toLong() and 0xFFFFFFFFL
            val so = xattrBlk * blk + id * 4
            val sbuf = le(read(so, minOf(4096L, raf.length() - so).toInt()))
            parseEntries(sbuf, sbuf.limit(), out, 1)
        }
        b.position(12 + 4 * shared)
        parseEntries(b, raw.size, out)
        return out
    }

    // ------------------------------------------------------------ model zmian

    sealed class Node {
        abstract var mode: Int; abstract var uid: Int; abstract var gid: Int
        abstract var xattrs: List<Xattr>
    }
    class NewFile(override var mode: Int, override var uid: Int, override var gid: Int, override var xattrs: List<Xattr>, val data: ByteArray) : Node()
    class NewDir(override var mode: Int, override var uid: Int, override var gid: Int, override var xattrs: List<Xattr>) : Node() {
        val children = LinkedHashMap<String, Node>()
    }

    /** Katalogi z istniejacego obrazu, ktorych zawartosc sie zmienia: nid -> (nazwa -> nid istniejacy albo Node nowy). */
    private val dirEdits = LinkedHashMap<Long, LinkedHashMap<String, Any>>()
    private val replaced = LinkedHashMap<Long, ByteArray>()

    private fun editableDir(nid: Long): LinkedHashMap<String, Any> = dirEdits.getOrPut(nid) {
        LinkedHashMap<String, Any>().also { m -> readDir(nid).forEach { if (it.name != "." && it.name != "..") m[it.name] = it.nid } }
    }

    private fun splitPath(path: String): Pair<String, String> {
        val p = path.trim('/')
        val i = p.lastIndexOf('/')
        return if (i < 0) "" to p else p.substring(0, i) to p.substring(i + 1)
    }

    /** Dziecko katalogu z uwzglednieniem zmian (Long = istniejacy nid, Node = nowy, null = brak). */
    private fun childOf(dirNid: Long, name: String): Any? {
        val e = dirEdits[dirNid]
        return if (e != null) e[name] else readDir(dirNid).firstOrNull { it.name == name }?.nid
    }

    /** Zwraca obiekt katalogu nadrzednego (Long = istniejacy nid, NewDir = nowy). */
    private fun resolveDir(path: String): Any? {
        var cur: Any = rootNid
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur = when (cur) {
                is Long -> childOf(cur, part) ?: return null
                is NewDir -> cur.children[part] ?: return null
                else -> return null
            }
            if (cur is Long && !inode(cur).isDir) return null
        }
        return cur
    }

    fun exists(path: String): Boolean {
        val (d, n) = splitPath(path)
        return when (val dir = resolveDir(d)) {
            is Long -> childOf(dir, n) != null
            is NewDir -> dir.children.containsKey(n)
            else -> false
        }
    }

    /** Podmienia zawartosc istniejacego pliku albo tworzy nowy (atrybuty z [template] albo z pliku obok). */
    fun putFile(path: String, data: ByteArray, template: Node? = null) {
        val (d, n) = splitPath(path)
        when (val dir = resolveDir(d) ?: throw IOException("Brak katalogu: $d")) {
            is Long -> {
                val existing = childOf(dir, n)
                if (existing is Long) {
                    if (!inode(existing).isReg) throw IOException("$path nie jest plikiem")
                    replaced[existing] = data
                } else if (existing is NewFile) {
                    editableDir(dir)[n] = NewFile(existing.mode, existing.uid, existing.gid, existing.xattrs, data)
                } else {
                    val t = template ?: siblingTemplate(dir) ?: throw IOException("Brak wzoru atrybutow dla $path")
                    editableDir(dir)[n] = NewFile(t.mode, t.uid, t.gid, t.xattrs, data)
                }
            }
            is NewDir -> {
                val prev = dir.children[n]
                val t = template ?: (prev as? NewFile) ?: dir.children.values.firstOrNull { it is NewFile } ?: NewFile(0x81A4, dir.uid, dir.gid, dir.xattrs, data)
                dir.children[n] = NewFile(t.mode, t.uid, t.gid, t.xattrs, data)
            }
        }
    }

    fun mkdir(path: String, template: Node): NewDir {
        val (d, n) = splitPath(path)
        val nd = NewDir(template.mode, template.uid, template.gid, template.xattrs)
        when (val dir = resolveDir(d) ?: throw IOException("Brak katalogu: $d")) {
            is Long -> editableDir(dir)[n] = nd
            is NewDir -> dir.children[n] = nd
        }
        return nd
    }

    fun delete(path: String): Boolean {
        val (d, n) = splitPath(path)
        return when (val dir = resolveDir(d)) {
            is Long -> editableDir(dir).remove(n) != null
            is NewDir -> dir.children.remove(n) != null
            else -> false
        }
    }

    /** Atrybuty istniejacego wpisu jako wzor dla nowych plikow/katalogow. */
    fun templateOf(path: String): Node? = lookup(path)?.let { attrsOf(it) }

    private fun siblingTemplate(dirNid: Long): Node? {
        val f = readDir(dirNid).firstOrNull { it.type == FT_REG } ?: return null
        val i = inode(f.nid)
        return NewFile(i.mode, i.uid, i.gid, xattrs(i), ByteArray(0))
    }

    // ------------------------------------------------------------ zapis

    private var tail = 0L

    private fun alloc(len: Long, align: Int): Long {
        tail = (tail + align - 1) / align * align
        val at = tail
        tail += len
        return at
    }

    private fun write(off: Long, data: ByteArray) { raf.seek(off); raf.write(data) }

    private fun xattrBody(list: List<Xattr>): ByteArray {
        if (list.isEmpty()) return ByteArray(0)
        var size = 12
        list.forEach { size += (4 + it.name.size + it.value.size + 3) / 4 * 4 }
        val b = le(ByteArray(size))
        b.putInt(0, 0) // h_name_filter = 0: "moze byc" - bezpieczne
        b.position(12)
        list.forEach {
            b.put(it.name.size.toByte()).put(it.index.toByte()).putShort(it.value.size.toShort()).put(it.name).put(it.value)
            while (b.position() and 3 != 0) b.put(0)
        }
        return b.array()
    }

    private fun encodeDir(entries: List<Dirent>): ByteArray {
        val sorted = entries.sortedWith { a, b -> cmpBytes(a.name.toByteArray(), b.name.toByteArray()) }
        val blocks = ArrayList<ByteArray>()
        var i = 0
        var lastUsed = 0
        while (i < sorted.size) {
            var n = 0; var used = 0
            while (i + n < sorted.size) {
                val add = 12 + sorted[i + n].name.toByteArray().size
                if (used + add > dirBlk) break
                used += add; n++
            }
            if (n == 0) throw IOException("Za dluga nazwa")
            val b = le(ByteArray(dirBlk))
            var noff = 12 * n
            for (k in 0 until n) {
                val e = sorted[i + k]
                b.putLong(12 * k, e.nid).putShort(12 * k + 8, noff.toShort())
                b.put(12 * k + 10, e.type.toByte())
                val nb = e.name.toByteArray()
                System.arraycopy(nb, 0, b.array(), noff, nb.size)
                noff += nb.size
            }
            blocks += b.array(); lastUsed = noff
            i += n
        }
        val out = ByteArray((blocks.size - 1) * dirBlk + lastUsed)
        blocks.forEachIndexed { k, a -> System.arraycopy(a, 0, out, k * dirBlk, if (k == blocks.size - 1) lastUsed else dirBlk) }
        return out
    }

    private fun ftOf(mode: Int) = when (mode and 0xF000) {
        0x8000 -> FT_REG; 0x4000 -> FT_DIR; 0xA000 -> FT_SYMLINK; 0x2000 -> 3; 0x6000 -> 4; 0x1000 -> 5; 0xC000 -> 6; else -> 0
    }

    private fun extInode(layout: Int, xattrs: ByteArray, mode: Int, size: Long, blkAddr: Long, ino: Long, uid: Int, gid: Int, nlink: Int): ByteArray {
        val b = le(ByteArray(64 + xattrs.size))
        val icount = if (xattrs.isEmpty()) 0 else (xattrs.size - 12) / 4 + 1
        b.putShort(0, ((layout shl 1) or 1).toShort())
        b.putShort(2, icount.toShort())
        b.putShort(4, mode.toShort())
        b.putLong(8, size)
        b.putInt(16, blkAddr.toInt())
        b.putInt(20, ino.toInt())
        b.putInt(24, uid).putInt(28, gid)
        b.putLong(32, 1230768000L) // jak build Androida (-T)
        b.putInt(44, nlink)
        System.arraycopy(xattrs, 0, b.array(), 64, xattrs.size)
        return b.array()
    }

    /** Zapisuje wszystkie zmiany. Zwraca liczbe bajtow dopisanych do obrazu. */
    fun commit(): Long {
        val start = maxOf(raf.length(), (le(sb).getInt(36).toLong() and 0xFFFFFFFFL) * blk)
        tail = (start + blk - 1) / blk * blk

        // 1) nidy dla nowych wezlow (i-wezel + xattry, bez przekraczania granicy bloku)
        val nidOf = HashMap<Node, Long>()
        val xbody = HashMap<Node, ByteArray>()
        fun allocInode(n: Node) {
            val x = xattrBody(n.xattrs); xbody[n] = x
            val len = 64L + x.size
            var at = (tail + 31) / 32 * 32
            if (at / blk != (at + len - 1) / blk && len <= blk) at = (at + blk - 1) / blk * blk
            tail = at + len
            nidOf[n] = (at - metaBlk * blk) / 32
            if (n is NewDir) n.children.values.forEach { allocInode(it) }
        }
        dirEdits.values.forEach { m -> m.values.forEach { if (it is Node) allocInode(it) } }

        var nextIno = inos + 1
        // 2) dane: podmienione pliki
        for ((nid, data) in replaced) {
            val at = alloc(data.size.toLong(), blk)
            write(at, data)
            patchExisting(nid, LAYOUT_PLAIN, data.size.toLong(), at / blk, null)
        }
        // 3) nowe wezly (rekurencyjnie)
        fun writeNode(n: Node, parentNid: Long) {
            val nid = nidOf[n]!!
            val ino = nextIno++
            when (n) {
                is NewFile -> {
                    val at = alloc(n.data.size.toLong(), blk)
                    write(at, n.data)
                    write(nidOffset(nid), extInode(LAYOUT_PLAIN, xbody[n]!!, n.mode, n.data.size.toLong(), at / blk, ino, n.uid, n.gid, 1))
                }
                is NewDir -> {
                    n.children.values.forEach { writeNode(it, nid) }
                    val ents = ArrayList<Dirent>()
                    ents += Dirent(".", nid, FT_DIR); ents += Dirent("..", parentNid, FT_DIR)
                    n.children.forEach { (name, c) -> ents += Dirent(name, nidOf[c]!!, ftOf(c.mode)) }
                    val data = encodeDir(ents)
                    val at = alloc(data.size.toLong(), blk)
                    write(at, data)
                    val subdirs = n.children.values.count { it is NewDir }
                    write(nidOffset(nid), extInode(LAYOUT_PLAIN, xbody[n]!!, n.mode, data.size.toLong(), at / blk, ino, n.uid, n.gid, 2 + subdirs))
                }
            }
        }
        // 4) przebudowane katalogi istniejace
        for ((dnid, map) in dirEdits) {
            val old = readDir(dnid)
            val dot = old.first { it.name == "." }; val dotdot = old.first { it.name == ".." }
            map.values.forEach { if (it is Node) writeNode(it, dnid) }
            val ents = ArrayList<Dirent>()
            ents += Dirent(".", dot.nid, FT_DIR); ents += Dirent("..", dotdot.nid, FT_DIR)
            var subdirs = 0
            map.forEach { (name, v) ->
                val e = when (v) {
                    is Long -> { val t = old.firstOrNull { it.nid == v && it.name == name }?.type ?: ftOf(inode(v).mode); Dirent(name, v, t) }
                    is Node -> Dirent(name, nidOf[v]!!, ftOf(v.mode))
                    else -> throw IllegalStateException()
                }
                if (e.type == FT_DIR) subdirs++
                ents += e
            }
            val data = encodeDir(ents)
            val at = alloc(data.size.toLong(), blk)
            write(at, data)
            patchExisting(dnid, LAYOUT_PLAIN, data.size.toLong(), at / blk, 2 + subdirs)
        }
        // 5) koniec obrazu wyrownany do bloku + superblok
        val end = (tail + blk - 1) / blk * blk
        raf.setLength(maxOf(raf.length(), end))
        val b = le(sb)
        b.putInt(36, (raf.length() / blk).toInt())
        b.putLong(16, nextIno - 1)
        if (featureCompat and COMPAT_SB_CHKSUM != 0) {
            val blockFromSb = read(1024, blk - 1024)
            System.arraycopy(sb, 0, blockFromSb, 0, 128)
            le(blockFromSb).putInt(4, 0)
            le(sb).putInt(4, crc32c(blockFromSb))
        }
        write(1024, sb)
        val added = raf.length() - start
        dirEdits.clear(); replaced.clear()
        return added
    }

    /** Przepina istniejacy i-wezel na nowe dane w ukladzie PLAIN (zostaja xattry, uid/gid, tryb). */
    private fun patchExisting(nid: Long, layout: Int, size: Long, blkAddr: Long, nlink: Int?) {
        val i = inode(nid)
        val h = le(read(i.offset, i.inodeSize))
        val fmt = (h.getShort(0).toInt() and 0xFFFF and 0x1.inv() and (7 shl 1).inv()) or (layout shl 1) or (if (i.extended) 1 else 0)
        h.putShort(0, fmt.toShort())
        if (i.extended) {
            h.putLong(8, size); h.putInt(16, blkAddr.toInt())
            if (nlink != null) h.putInt(44, nlink)
        } else {
            if (size > 0xFFFFFFFFL) throw IOException("Za duzy plik dla kompaktowego i-wezla")
            h.putInt(8, size.toInt()); h.putInt(16, blkAddr.toInt())
            if (nlink != null) h.putShort(6, nlink.toShort())
        }
        write(i.offset, h.array())
    }

    override fun close() = raf.close()

    companion object {
        const val MAGIC = 0xE0F5E1E2.toInt()
        const val LAYOUT_PLAIN = 0
        const val LAYOUT_INLINE = 2
        const val FT_REG = 1
        const val FT_DIR = 2
        const val FT_SYMLINK = 7
        const val COMPAT_SB_CHKSUM = 1

        private fun le(a: ByteArray): ByteBuffer = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)

        private fun cmpBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        private val CRC_TABLE = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var c = i
                repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1 }
                t[i] = c
            }
        }

        /** crc32c(~0, buf) bez koncowej negacji - tak liczy EROFS sumy superbloku. */
        fun crc32c(buf: ByteArray): Int {
            var c = -1
            for (x in buf) c = CRC_TABLE[(c xor x.toInt()) and 0xFF] xor (c ushr 8)
            return c
        }
    }
}
