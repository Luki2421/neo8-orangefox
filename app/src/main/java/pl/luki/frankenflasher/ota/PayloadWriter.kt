package pl.luki.frankenflasher.ota

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.BasicArrayCache
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Rozpakowanie partycji z payload.bin z rownolegloscia na poziomie operacji.
 *
 * Pelne OTA dzieli kazda partycje na niezalezne operacje (zwykle 2 MB po rozpakowaniu,
 * kazda osobno skompresowana XZ/ZSTD/BZ2). Kazda operacja jest osobnym zadaniem, wiec
 * wszystkie rdzenie pracuja tez nad jedna duza partycja; zapis pozycyjny (pwrite).
 *
 * Suma SHA-256 partycji jest liczona od razu z danych w pamieci (bez ponownego czytania
 * calego obrazu z dysku): operacje koncza sie w dowolnej kolejnosci, wiec ich wyniki czekaja
 * w pamieci, az wszystko przed nimi (w kolejnosci blokow) zostanie policzone; dziury
 * (ZERO/DISCARD i bloki bez operacji) to zera. Gdy sie nie da (operacja > 16 MB, nakladajace
 * sie bloki albo za duzo czekajacych danych), [Target.onDone] dostaje null i suma jest liczona
 * z pliku jak dawniej.
 */
object PayloadWriter {
    /** [onDone] raz, w watku roboczym, po zapisaniu wszystkich operacji: true/false = suma policzona
     *  w locie zgodna/niezgodna, null = nie policzona (brak sumy w OTA albo trzeba policzyc z pliku). */
    class Target(val part: PartUpdate, val out: FileChannel, val onDone: (Boolean?) -> Unit)

    /** Pomiar czasu watkow (nanosekundy, suma po watkach) - co ogranicza predkosc rozpakowywania. */
    class Stats {
        val read = AtomicLong(); val unpack = AtomicLong(); val write = AtomicLong(); val hash = AtomicLong(); val close = AtomicLong()
        val bytesByType = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
        var wallNs = 0L; var threads = 1
        fun summary(): String {
            val busy = (read.get() + unpack.get() + write.get() + hash.get() + close.get()).coerceAtLeast(1)
            fun pct(v: AtomicLong) = "%.0f%%".format(v.get() * 100.0 / busy)
            val util = busy * 100.0 / (wallNs.coerceAtLeast(1) * threads)
            val types = bytesByType.entries.sortedByDescending { it.value.get() }.joinToString(", ") { "${it.key} ${it.value.get() shr 20} MB" }
            return "odczyt ${pct(read)} · rozpakowanie ${pct(unpack)} · zapis ${pct(write)} · suma ${pct(hash)} · zamkniecie ${pct(close)}" +
                " · watki zajete %.0f%% (%d)".format(util, threads) + " · dane: $types"
        }
    }

    /** Operacja wieksza od tego jest czytana i zapisywana strumieniowo (bez sumy w locie). */
    private const val MAX_IN_MEMORY = 16L shl 20
    /** Ile danych moze czekac na policzenie sumy jednej partycji; wiecej = suma z pliku. */
    private const val MAX_PENDING = 64L shl 20

    private val outBuf = ThreadLocal.withInitial { ByteArray(1 shl 20) }
    private val ZEROS = ByteArray(1 shl 20)

    /** Liczba watkow: rdzenie, max 8, i nie wiecej niz pozwala sterta (~40 MB na watek z zapasem). */
    fun defaultThreads(): Int {
        val rt = Runtime.getRuntime()
        val byHeap = (rt.maxMemory() / (40L shl 20)).toInt()
        return minOf(rt.availableProcessors(), 8, byHeap).coerceAtLeast(1)
    }

    /** Fragment partycji w kolejnosci blokow: [len] bajtow z wyniku operacji [op] od [opOff] (op < 0 = zera). */
    private class Piece(val start: Long, val len: Long, val op: Int, val opOff: Long)

    /** Liczenie SHA-256 w kolejnosci blokow z wynikow operacji konczacych sie w dowolnej kolejnosci. */
    private class Hasher(val part: PartUpdate, bs: Long) {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        var pieces: List<Piece>? = null
        val opPieces = IntArray(part.ops.size)
        val data = HashMap<Int, ByteArray>()
        val ready = BooleanArray(part.ops.size)
        var next = 0
        var pending = 0L
        var failed = false

        init {
            val list = ArrayList<Piece>()
            var ok = part.hash != null
            for ((i, op) in part.ops.withIndex()) {
                if (!ok) break
                if (op.dst.sumOf { it.num } * bs > MAX_IN_MEMORY) { ok = false; break }
                var off = 0L
                for (e in op.dst) {
                    val zero = op.type == OpType.ZERO || op.type == OpType.DISCARD
                    list += Piece(e.start * bs, e.num * bs, if (zero) -1 else i, off)
                    if (!zero) opPieces[i]++
                    off += e.num * bs
                }
            }
            if (ok) {
                list.sortBy { it.start }
                val full = ArrayList<Piece>()
                var pos = 0L
                for (p in list) {
                    if (p.start < pos) { ok = false; break } // nakladajace sie bloki
                    if (p.start > pos) full += Piece(pos, p.start - pos, -1, 0)
                    if (p.start + p.len > part.size) { ok = false; break }
                    full += p; pos = p.start + p.len
                }
                if (ok && pos < part.size) full += Piece(pos, part.size - pos, -1, 0)
                if (ok) pieces = full
            }
            failed = !ok
        }

        /** Wynik operacji [i] (null = operacja bez danych). */
        @Synchronized fun add(i: Int, out: ByteArray?) {
            if (failed) return
            ready[i] = true
            if (out != null && opPieces[i] > 0) { data[i] = out; pending += out.size }
            if (pending > MAX_PENDING) { drop(); return }
            val ps = pieces!!
            while (next < ps.size) {
                val p = ps[next]
                if (p.op >= 0 && !ready[p.op]) break
                if (p.op < 0) {
                    var left = p.len
                    while (left > 0) { val n = minOf(left, ZEROS.size.toLong()).toInt(); md.update(ZEROS, 0, n); left -= n }
                } else {
                    val b = data[p.op]!!
                    md.update(b, p.opOff.toInt(), p.len.toInt())
                    if (--opPieces[p.op] == 0) { pending -= b.size; data.remove(p.op) }
                }
                next++
            }
        }

        @Synchronized fun drop() { failed = true; data.clear(); pending = 0 }

        @Synchronized fun result(): Boolean? =
            if (failed || part.hash == null || next != pieces!!.size) null else md.digest().contentEquals(part.hash)
    }

    fun run(src: FileChannel, info: PayloadInfo, targets: List<Target>, threads: Int, onBytes: (Long) -> Unit): Stats {
        val st = Stats(); st.threads = threads.coerceAtLeast(1); val wall0 = System.nanoTime()
        val bs = info.blockSize.toLong()
        val failed = AtomicReference<Throwable>()
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val futures = ArrayList<Future<*>>()
            for (t in targets) {
                val left = AtomicInteger(t.part.ops.size)
                val hasher = Hasher(t.part, bs)
                val finish = Runnable {
                    if (failed.get() != null) return@Runnable
                    try { val c0 = System.nanoTime(); t.out.force(false); t.onDone(hasher.result()); st.close.addAndGet(System.nanoTime() - c0) } catch (e: Throwable) { failed.compareAndSet(null, e); throw e }
                }
                if (t.part.ops.isEmpty()) { futures += pool.submit(finish); continue }
                for ((i, op) in t.part.ops.withIndex()) futures += pool.submit(Runnable {
                    if (failed.get() != null) return@Runnable
                    try {
                        val out = writeOp(src, info, op, t.out, bs, onBytes, st)
                        val h0 = System.nanoTime()
                        if (out === STREAMED) hasher.drop() else hasher.add(i, out)
                        st.hash.addAndGet(System.nanoTime() - h0)
                    } catch (e: Throwable) {
                        failed.compareAndSet(null, IllegalStateException("${t.part.name}: ${e.message ?: e}", e)); throw e
                    }
                    if (left.decrementAndGet() == 0) finish.run()
                })
            }
            for (f in futures) {
                try { f.get() } catch (e: ExecutionException) { break }
                if (failed.get() != null) break
            }
            failed.get()?.let { throw it }
            st.wallNs = System.nanoTime() - wall0
            return st
        } finally {
            pool.shutdownNow()
        }
    }

    private val STREAMED = ByteArray(0)

    private fun typeName(t: Int) = when (t) {
        OpType.REPLACE -> "REPLACE"; OpType.REPLACE_BZ -> "BZ2"; OpType.REPLACE_XZ -> "XZ"; OpType.ZSTD -> "ZSTD"
        OpType.ZERO -> "ZERO"; OpType.DISCARD -> "DISCARD"; else -> "typ $t"
    }

    /** Zapisuje operacje; zwraca jej wynik (dla sumy), null dla ZERO/DISCARD, [STREAMED] dla duzych operacji. */
    private fun writeOp(src: FileChannel, info: PayloadInfo, op: Op, out: FileChannel, bs: Long, onBytes: (Long) -> Unit, st: Stats): ByteArray? {
        val outLen = op.dst.sumOf { it.num } * bs
        st.bytesByType.getOrPut(typeName(op.type)) { AtomicLong() }.addAndGet(outLen)
        if (op.type == OpType.ZERO || op.type == OpType.DISCARD) { // dziury w pliku (ftruncate) = zera
            onBytes(outLen); return null
        }
        val at = info.dataStart + op.dataOffset
        // Jeden odczyt calej skompresowanej operacji zamiast wielu malych (wazne przy odczycie przez SAF/FUSE).
        val r0 = System.nanoTime()
        val raw: InputStream = if (op.dataLength <= MAX_IN_MEMORY)
            ByteArrayInputStream(PayloadParser.readAt(src, at, op.dataLength.toInt())).also { st.read.addAndGet(System.nanoTime() - r0) }
        else RegionInputStream(src, at, op.dataLength).buffered(1 shl 20)
        val ins: InputStream = when (op.type) {
            OpType.REPLACE -> raw
            // Cache tablic: slownik i bufory LZMA uzywane ponownie zamiast alokacji dla kazdej operacji.
            OpType.REPLACE_XZ -> XZInputStream(raw, -1, true, BasicArrayCache.getInstance())
            OpType.REPLACE_BZ -> BZip2CompressorInputStream(raw)
            OpType.ZSTD -> ZstdInputStream(raw)
            else -> throw IllegalStateException("op ${op.type}")
        }
        ins.use {
            if (outLen <= MAX_IN_MEMORY) {
                // Caly wynik w pamieci: zapis + suma w locie.
                val u0 = System.nanoTime()
                val data = ByteArray(outLen.toInt())
                var got = 0
                while (got < data.size) { val r = ins.read(data, got, data.size - got); if (r < 0) break; got += r }
                val w0 = System.nanoTime(); st.unpack.addAndGet(w0 - u0)
                var off = 0L
                for (e in op.dst) {
                    var pos = e.start * bs
                    val len = e.num * bs
                    val bb = ByteBuffer.wrap(data, off.toInt(), len.toInt())
                    while (bb.hasRemaining()) pos += out.write(bb, pos)
                    off += len
                }
                st.write.addAndGet(System.nanoTime() - w0)
                onBytes(outLen)
                return data
            }
            val buf = outBuf.get()!!
            for (e in op.dst) {
                var pos = e.start * bs
                var left = e.num * bs
                while (left > 0) {
                    val want = minOf(left, buf.size.toLong()).toInt()
                    var got = 0
                    val u0 = System.nanoTime()
                    while (got < want) { val r = ins.read(buf, got, want - got); if (r < 0) break; got += r }
                    val w0 = System.nanoTime(); st.unpack.addAndGet(w0 - u0)
                    if (got == 0) break
                    val bb = ByteBuffer.wrap(buf, 0, got)
                    while (bb.hasRemaining()) pos += out.write(bb, pos)
                    st.write.addAndGet(System.nanoTime() - w0)
                    left -= got
                    onBytes(got.toLong())
                    if (got < want) break
                }
            }
            return STREAMED
        }
    }
}
