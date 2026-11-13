package pl.luki.frankenflasher.fastboot

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimalna obsluga formatu Android sparse: wykrywanie i dzielenie obrazu raw na kawalki sparse. */
object Sparse {
    const val MAGIC = 0xED26FF3A.toInt()
    const val BLK = 4096
    private const val FILE_HDR = 28
    private const val CHUNK_HDR = 12
    private const val RAW: Short = 0xCAC1.toShort()
    private const val DONT_CARE: Short = 0xCAC3.toShort()

    fun isSparse(input: InputStream): Boolean {
        val b = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = input.read(b, n, 4 - n)
            if (r < 0) return false
            n += r
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int == MAGIC
    }

    data class Piece(val startBlk: Long, val numBlk: Long, val totalBlk: Long) {
        val before get() = startBlk > 0
        val after get() = startBlk + numBlk < totalBlk
        val chunks get() = (if (before) 1 else 0) + 1 + (if (after) 1 else 0)
        val byteSize get() = FILE_HDR.toLong() + CHUNK_HDR.toLong() * chunks + numBlk * BLK
    }

    /** Dzieli obraz raw o rozmiarze [rawSize] na kawalki mieszczace sie w [maxDownload]. */
    fun plan(rawSize: Long, maxDownload: Long): List<Piece> {
        val total = (rawSize + BLK - 1) / BLK
        val per = ((maxDownload - FILE_HDR - 3L * CHUNK_HDR) / BLK).coerceAtLeast(1)
        val out = ArrayList<Piece>()
        var s = 0L
        while (s < total) {
            val n = minOf(per, total - s)
            out += Piece(s, n, total)
            s += n
        }
        return out
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** Naglowek pliku + chunk DONT_CARE (przed) + naglowek chunka RAW. */
    fun head(p: Piece): ByteArray {
        val bb = le(FILE_HDR + CHUNK_HDR * 2)
        bb.putInt(MAGIC).putShort(1).putShort(0)
            .putShort(FILE_HDR.toShort()).putShort(CHUNK_HDR.toShort())
            .putInt(BLK).putInt(p.totalBlk.toInt()).putInt(p.chunks).putInt(0)
        if (p.before) bb.putShort(DONT_CARE).putShort(0).putInt(p.startBlk.toInt()).putInt(CHUNK_HDR)
        bb.putShort(RAW).putShort(0).putInt(p.numBlk.toInt()).putInt((CHUNK_HDR + p.numBlk * BLK).toInt())
        return bb.array().copyOf(bb.position())
    }

    /** Chunk DONT_CARE (po) albo pusta tablica. */
    fun tail(p: Piece): ByteArray {
        if (!p.after) return ByteArray(0)
        val bb = le(CHUNK_HDR)
        bb.putShort(DONT_CARE).putShort(0).putInt((p.totalBlk - p.startBlk - p.numBlk).toInt()).putInt(CHUNK_HDR)
        return bb.array()
    }
}

/**
 * Dzieli istniejacy obraz sparse na czesci <= limit (jak "resparse" w fastboot na PC).
 * Przebieg 1 (plan) czyta tylko naglowki chunkow, przebieg 2 (write) wysyla dane strumieniowo.
 */
class SparseResplitter private constructor(
    val blkSize: Int,
    val totalBlocks: Long,
    val pieces: List<Piece>,
) {
    class Seg(val type: Short, val blocks: Long, val srcOffset: Long, val dataLen: Long, val fill: ByteArray?)
    class Piece(val startBlk: Long, val blocks: Long, val segs: List<Seg>) {
        fun chunks(total: Long) = segs.size + (if (startBlk > 0) 1 else 0) + (if (startBlk + blocks < total) 1 else 0)
        fun byteSize(total: Long) = 28L + 12L * chunks(total) + segs.sumOf { it.dataLen }
    }

    val rawSize get() = totalBlocks * blkSize

    companion object {
        private const val RAW: Short = 0xCAC1.toShort()
        private const val FILL: Short = 0xCAC2.toShort()
        private const val DONT_CARE: Short = 0xCAC3.toShort()
        private const val CRC: Short = 0xCAC4.toShort()

        private fun readFully(i: InputStream, b: ByteArray, n: Int) {
            var o = 0
            while (o < n) { val r = i.read(b, o, n - o); if (r < 0) throw java.io.EOFException(); o += r }
        }
        private fun skipFully(i: InputStream, n: Long) {
            var left = n
            val tmp = ByteArray(64 * 1024)
            while (left > 0) {
                val s = i.skip(left)
                if (s > 0) { left -= s; continue }
                val r = i.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
                if (r < 0) throw java.io.EOFException()
                left -= r
            }
        }

        fun plan(input: InputStream, limit: Long): SparseResplitter {
            val h = ByteArray(28)
            readFully(input, h, 28)
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != Sparse.MAGIC) throw IllegalArgumentException("To nie jest obraz sparse")
            val fileHdr = bb.getShort(8).toInt() and 0xFFFF
            val chunkHdr = bb.getShort(10).toInt() and 0xFFFF
            val blk = bb.getInt(12)
            val totalBlk = bb.getInt(16).toLong() and 0xFFFFFFFFL
            val totalChunks = bb.getInt(20).toLong() and 0xFFFFFFFFL
            if (fileHdr > 28) skipFully(input, (fileHdr - 28).toLong())
            var pos = fileHdr.toLong()

            val budget = limit - 28 - 24 // naglowek pliku + DONT_CARE przed i po
            val pieces = ArrayList<Piece>()
            var cur = ArrayList<Seg>()
            var curStart = 0L
            var curBlocks = 0L
            var curBytes = 0L
            var blkPos = 0L
            fun flush() {
                if (cur.isNotEmpty()) pieces += Piece(curStart, curBlocks, cur)
                cur = ArrayList(); curStart = blkPos; curBlocks = 0; curBytes = 0
            }
            fun add(s: Seg) {
                val sz = 12 + s.dataLen
                if (curBytes + sz > budget && cur.isNotEmpty()) flush()
                cur += s; curBlocks += s.blocks; curBytes += sz; blkPos += s.blocks
            }
            val ch = ByteArray(chunkHdr)
            for (c in 0 until totalChunks) {
                readFully(input, ch, chunkHdr)
                pos += chunkHdr
                val cb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                val type = cb.getShort(0)
                val blocks = cb.getInt(4).toLong() and 0xFFFFFFFFL
                val dataLen = (cb.getInt(8).toLong() and 0xFFFFFFFFL) - chunkHdr
                when (type) {
                    RAW -> {
                        var off = pos
                        var left = blocks
                        val maxBlk = ((budget - 12) / blk).coerceAtLeast(1)
                        while (left > 0) {
                            val room = ((budget - curBytes - 12) / blk)
                            val n = if (room >= 1) minOf(left, room) else minOf(left, maxBlk)
                            add(Seg(RAW, n, off, n * blk, null))
                            off += n * blk; left -= n
                        }
                        skipFully(input, dataLen)
                    }
                    FILL -> {
                        val f = ByteArray(4); readFully(input, f, 4)
                        if (dataLen > 4) skipFully(input, dataLen - 4)
                        add(Seg(FILL, blocks, -1, 4, f))
                    }
                    DONT_CARE -> { skipFully(input, dataLen); add(Seg(DONT_CARE, blocks, -1, 0, null)) }
                    CRC -> skipFully(input, dataLen)
                    else -> throw IllegalArgumentException("Nieznany chunk sparse 0x%04x".format(type.toInt() and 0xFFFF))
                }
                pos += dataLen
            }
            flush()
            return SparseResplitter(blk, totalBlk, pieces)
        }
    }

    /** Wysyla czesc [p]; [src] to strumien na pozycji [posRef][0], przesuwany tylko do przodu. */
    fun writePiece(p: Piece, src: InputStream, posRef: LongArray, out: (ByteArray, Int, Int) -> Unit, onBytes: (Long) -> Unit) {
        val bb = ByteBuffer.allocate(28 + 12).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(Sparse.MAGIC).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(blkSize).putInt(totalBlocks.toInt()).putInt(p.chunks(totalBlocks)).putInt(0)
        if (p.startBlk > 0) bb.putShort(DONT_CARE).putShort(0).putInt(p.startBlk.toInt()).putInt(12)
        out(bb.array(), 0, bb.position())
        val buf = ByteArray(1 shl 20)
        for (s in p.segs) {
            val hb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            hb.putShort(s.type).putShort(0).putInt(s.blocks.toInt()).putInt((12 + s.dataLen).toInt())
            out(hb.array(), 0, 12)
            when (s.type) {
                RAW -> {
                    val skip = s.srcOffset - posRef[0]
                    if (skip < 0) throw IllegalStateException("sparse: cofanie strumienia")
                    skipFully(src, skip); posRef[0] += skip
                    var left = s.dataLen
                    while (left > 0) {
                        val n = minOf(left, buf.size.toLong()).toInt()
                        readFully(src, buf, n)
                        out(buf, 0, n)
                        left -= n; posRef[0] += n
                        onBytes(n.toLong())
                    }
                }
                FILL -> out(s.fill!!, 0, 4)
            }
        }
        val after = totalBlocks - p.startBlk - p.blocks
        if (after > 0) {
            val tb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            tb.putShort(DONT_CARE).putShort(0).putInt(after.toInt()).putInt(12)
            out(tb.array(), 0, 12)
        }
    }
}
