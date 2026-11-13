package pl.luki.frankenflasher.ota

/** Minimalny czytnik protobuf (varint / length-delimited) - wystarczy do manifestu payload.bin. */
class Pb(private val b: ByteArray, private var p: Int = 0, private val end: Int = b.size) {
    fun more() = p < end

    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            val x = b[p++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }

    /** Zwraca (numer pola, typ). */
    fun tag(): Pair<Int, Int> { val k = varint(); return (k ushr 3).toInt() to (k and 7).toInt() }

    fun sub(): Pb { val len = varint().toInt(); val s = Pb(b, p, p + len); p += len; return s }
    fun string(): String { val len = varint().toInt(); val s = String(b, p, len, Charsets.UTF_8); p += len; return s }

    fun skip(wt: Int) {
        when (wt) {
            0 -> varint()
            1 -> p += 8
            2 -> { val n = varint().toInt(); p += n }
            5 -> p += 4
            else -> throw IllegalStateException("protobuf: nieznany typ $wt")
        }
    }
}
