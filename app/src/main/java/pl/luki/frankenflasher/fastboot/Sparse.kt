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
