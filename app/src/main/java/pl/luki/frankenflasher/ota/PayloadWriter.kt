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

    fun run(src: FileChannel, info: PayloadInfo, targets: List<Target>, threads: Int, onBytes: (Long) -> Unit) {
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
                    try { t.out.force(false); t.onDone(hasher.result()) } catch (e: Throwable) { failed.compareAndSet(null, e); throw e }
                }
                if (t.part.ops.isEmpty()) { futures += pool.submit(finish); continue }
                for ((i, op) in t.part.ops.withIndex()) futures += pool.submit(Runnable {
                    if (failed.get() != null) return@Runnable
                    try {
                        val out = writeOp(src, info, op, t.out, bs, onBytes)
                        if (out === STREAMED) hasher.drop() else hasher.add(i, out)
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
        } finally {
            pool.shutdownNow()
        }
    }

    private val STREAMED = ByteArray(0)

    /** Zapisuje operacje; zwraca jej wynik (dla sumy), null dla ZERO/DISCARD, [STREAMED] dla duzych operacji. */
    private fun writeOp(src: FileChannel, info: PayloadInfo, op: Op, out: FileChannel, bs: Long, onBytes: (Long) -> Unit): ByteArray? {
        val outLen = op.dst.sumOf { it.num } * bs
        if (op.type == OpType.ZERO || op.type == OpType.DISCARD) { // dziury w pliku (ftruncate) = zera
            onBytes(outLen); return null
        }
        val at = info.dataStart + op.dataOffset
        // Jeden odczyt calej skompresowanej operacji zamiast wielu malych (wazne przy odczycie przez SAF/FUSE).
        val raw: InputStream = if (op.dataLength <= MAX_IN_MEMORY)
            ByteArrayInputStream(PayloadParser.readAt(src, at, op.dataLength.toInt()))
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
                val data = ByteArray(outLen.toInt())
                var got = 0
                while (got < data.size) { val r = ins.read(data, got, data.size - got); if (r < 0) break; got += r }
                var off = 0L
                for (e in op.dst) {
                    var pos = e.start * bs
                    val len = e.num * bs
                    val bb = ByteBuffer.wrap(data, off.toInt(), len.toInt())
                    while (bb.hasRemaining()) pos += out.write(bb, pos)
                    off += len
                }
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
                    while (got < want) { val r = ins.read(buf, got, want - got); if (r < 0) break; got += r }
                    if (got == 0) break
                    val bb = ByteBuffer.wrap(buf, 0, got)
                    while (bb.hasRemaining()) pos += out.write(bb, pos)
                    left -= got
                    onBytes(got.toLong())
                    if (got < want) break
                }
            }
            return STREAMED
        }
    }
}
