package pl.luki.frankenflasher.ota

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.BasicArrayCache
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Rozpakowanie partycji z payload.bin z rownolegloscia na poziomie operacji.
 *
 * Pelne OTA dzieli kazda partycje na niezalezne operacje (zwykle 2 MB po
 * rozpakowaniu, kazda osobno skompresowana XZ/ZSTD/BZ2). Wczesniej jeden watek
 * rozpakowywal cala partycje, wiec najwieksza (np. my_product, system) zawsze
 * szla na jednym rdzeniu. Tu kazda operacja jest osobnym zadaniem i wszystkie
 * rdzenie pracuja tez nad jedna duza partycja. Zapis pozycyjny (pwrite) jest
 * bezpieczny dla wielu watkow.
 */
object PayloadWriter {
    /** [onDone] wywolywane raz, w watku roboczym, gdy wszystkie operacje partycji sa zapisane. */
    class Target(val part: PartUpdate, val out: FileChannel, val onDone: () -> Unit)

    /** Operacja wieksza od tego jest czytana strumieniowo zamiast w calosci do pamieci. */
    private const val MAX_BUFFERED = 16L shl 20

    private val outBuf = ThreadLocal.withInitial { ByteArray(1 shl 20) }

    /** Liczba watkow: rdzenie, max 8, i nie wiecej niz pozwala sterta (~40 MB na watek z zapasem). */
    fun defaultThreads(): Int {
        val rt = Runtime.getRuntime()
        val byHeap = (rt.maxMemory() / (40L shl 20)).toInt()
        return minOf(rt.availableProcessors(), 8, byHeap).coerceAtLeast(1)
    }

    fun run(src: FileChannel, info: PayloadInfo, targets: List<Target>, threads: Int, onBytes: (Long) -> Unit) {
        val bs = info.blockSize.toLong()
        val failed = AtomicReference<Throwable>()
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val futures = ArrayList<Future<*>>()
            for (t in targets) {
                val left = AtomicInteger(t.part.ops.size)
                val finish = Runnable {
                    if (failed.get() != null) return@Runnable
                    try { t.out.force(false); t.onDone() } catch (e: Throwable) { failed.compareAndSet(null, e); throw e }
                }
                if (t.part.ops.isEmpty()) { futures += pool.submit(finish); continue }
                for (op in t.part.ops) futures += pool.submit(Runnable {
                    if (failed.get() != null) return@Runnable
                    try {
                        writeOp(src, info, op, t.out, bs, onBytes)
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

    private fun writeOp(src: FileChannel, info: PayloadInfo, op: Op, out: FileChannel, bs: Long, onBytes: (Long) -> Unit) {
        if (op.type == OpType.ZERO || op.type == OpType.DISCARD) { // dziury w pliku (ftruncate) = zera
            onBytes(op.dst.sumOf { it.num } * bs); return
        }
        val at = info.dataStart + op.dataOffset
        // Jeden odczyt calej skompresowanej operacji zamiast wielu malych (wazne przy odczycie przez SAF/FUSE).
        val raw: InputStream = if (op.dataLength <= MAX_BUFFERED)
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
        val buf = outBuf.get()!!
        ins.use {
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
        }
    }
}
