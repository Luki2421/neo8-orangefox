package pl.luki.frankenflasher.ota

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Podzial partycji na foldery jak w FastbootFirmwareFlasher (+ partycje logiczne z metadanych OTA zawsze do SYSTEM). */
object Classifier {
    private val BOOT = listOf("boot", "init_boot", "recovery", "vbmeta", "vbmeta_system", "vbmeta_vendor", "vendor_boot", "dtbo")
    private val SYSTEM = listOf("my_bigball", "my_carrier", "my_company", "my_engineering", "my_heytap", "my_manifest", "my_preload",
        "my_product", "my_region", "my_stock", "odm", "odm_dlkm", "product", "system", "system_dlkm", "system_ext", "vendor", "vendor_dlkm")
    private val CRITICAL = listOf("abl", "aop", "aop_config", "bluetooth", "cpucp", "devcfg", "dsp", "engineering_cdt", "featenabler", "hyp",
        "imagefv", "keymaster", "oplus_sec", "oplusstanvbk", "qupfw", "shrm", "splash", "tz", "uefi", "uefisecapp", "apusys", "audio_dsp",
        "ccu", "cdt_engineering", "dpm", "gpueb", "gz", "lk", "mcf_ota", "mcump", "md1img", "mvpu_algo", "pi_img", "preloader_raw", "scp",
        "spmfw", "sspm", "tee", "multiimgoem", "qweslicstore", "vm-bootsys", "xbl", "xbl_config", "xbl_ramdump")

    fun folder(name: String, dynamic: Set<String>): String {
        val n = name.lowercase()
        return when {
            n in dynamic -> "SYSTEM"
            BOOT.any { n.startsWith(it) } -> "BOOTLOADER"
            n.startsWith("modem") -> "MODEM"
            SYSTEM.any { n.startsWith(it) } -> "SYSTEM"
            n.startsWith("my_") -> "SYSTEM"
            CRITICAL.any { n.removeSuffix("_a").removeSuffix("_b").startsWith(it) } -> "CRITICAL"
            else -> "EXTRA"
        }
    }
}

/** Rozpakowuje pelne OTA (payload.bin lub ZIP z payload.bin) do folderu SAF w ukladzie FFF. */
class OtaExtractor(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val progress: (Long, Long) -> Unit,
) {
    class Result(val tree: Uri, val folderDocId: String)

    fun extract(input: Uri, inputName: String, outTree: Uri, threads: Int = 3): Result {
        val pfd = ctx.contentResolver.openFileDescriptor(input, "r") ?: throw java.io.IOException("Nie mozna otworzyc $inputName")
        pfd.use {
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val base = if (inputName.endsWith(".zip", true)) {
                ZipLocator.findStored(ch, "payload.bin") ?: throw IllegalArgumentException("W ZIP nie ma payload.bin")
            } else 0L
            val info = PayloadParser.parse(ch, base)
            log("payload: ${info.parts.size} partycji, blok ${info.blockSize}, logicznych: ${info.dynamic.size}")
            for (p in info.parts) for (o in p.ops) {
                if (o.type !in setOf(OpType.REPLACE, OpType.REPLACE_BZ, OpType.REPLACE_XZ, OpType.ZSTD, OpType.ZERO, OpType.DISCARD))
                    throw IllegalArgumentException("${p.name}: operacja typu ${o.type} - to OTA przyrostowe, potrzebne pelne OTA")
            }

            val cr = ctx.contentResolver
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(outTree, DocumentsContract.getTreeDocumentId(outTree))
            val outName = inputName.substringBeforeLast('.').ifEmpty { "firmware" }
            val outDir = DocumentsContract.createDocument(cr, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, outName)
                ?: throw java.io.IOException("Nie mozna utworzyc folderu $outName")
            val groupDirs = HashMap<String, Uri>()
            for (g in listOf("BOOTLOADER", "CRITICAL", "EXTRA", "MODEM", "SYSTEM")) {
                groupDirs[g] = DocumentsContract.createDocument(cr, outDir, DocumentsContract.Document.MIME_TYPE_DIR, g)
                    ?: throw java.io.IOException("Nie mozna utworzyc $g")
            }

            val total = info.parts.sumOf { it.size }
            val done = AtomicLong()
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val futures = ArrayList<Future<*>>()
                for (p in info.parts.sortedByDescending { it.size }) {
                    futures += pool.submit(Runnable {
                        val g = Classifier.folder(p.name, info.dynamic)
                        val doc = DocumentsContract.createDocument(cr, groupDirs[g]!!, "application/octet-stream", "${p.name}.img")
                            ?: throw java.io.IOException("Nie mozna utworzyc ${p.name}.img")
                        writePartition(ch, info, p, doc) { n -> progress(done.addAndGet(n), total) }
                        log("  $g/${p.name}.img (${p.size / (1 shl 20)} MB)")
                    })
                }
                for (f in futures) try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            } finally {
                pool.shutdownNow()
            }
            log("Rozpakowano do: $outName")
            return Result(outTree, DocumentsContract.getDocumentId(outDir))
        }
    }

    private fun writePartition(src: FileChannel, info: PayloadInfo, p: PartUpdate, doc: Uri, onBytes: (Long) -> Unit) {
        val bs = info.blockSize.toLong()
        val opfd = ctx.contentResolver.openFileDescriptor(doc, "rw") ?: throw java.io.IOException("Zapis ${p.name} niemozliwy")
        opfd.use {
            Os.ftruncate(opfd.fileDescriptor, p.size)
            val out = FileOutputStream(opfd.fileDescriptor).channel
            val buf = ByteArray(1 shl 20)
            for (op in p.ops) {
                val opBytes = op.dst.sumOf { it.num } * bs
                if (op.type == OpType.ZERO || op.type == OpType.DISCARD) { onBytes(opBytes); continue } // dziury w pliku = zera
                val raw: InputStream = RegionInputStream(src, info.dataStart + op.dataOffset, op.dataLength)
                val ins: InputStream = when (op.type) {
                    OpType.REPLACE -> raw
                    OpType.REPLACE_XZ -> XZInputStream(raw.buffered(1 shl 16))
                    OpType.REPLACE_BZ -> BZip2CompressorInputStream(raw.buffered(1 shl 16))
                    OpType.ZSTD -> ZstdInputStream(raw.buffered(1 shl 16))
                    else -> throw IllegalStateException("op ${op.type}")
                }
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
            out.force(false)
        }
    }
}
