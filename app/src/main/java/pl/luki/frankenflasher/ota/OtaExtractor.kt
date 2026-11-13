package pl.luki.frankenflasher.ota

import pl.luki.frankenflasher.tr
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

    private fun openPayload(input: Uri, inputName: String): Pair<android.os.ParcelFileDescriptor, PayloadInfo> {
        val pfd = ctx.contentResolver.openFileDescriptor(input, "r") ?: throw java.io.IOException(tr("Nie mozna otworzyc $inputName", "Cannot open $inputName"))
        val ch = FileInputStream(pfd.fileDescriptor).channel
        val base = if (inputName.endsWith(".zip", true)) {
            ZipLocator.findStored(ch, "payload.bin") ?: throw IllegalArgumentException(tr("W ZIP nie ma payload.bin", "No payload.bin in the ZIP"))
        } else 0L
        return pfd to PayloadParser.parse(ch, base)
    }

    private fun sha256(uri: Uri, onBytes: (Long) -> Unit): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        (ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("odczyt $uri")).use { i ->
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); onBytes(n.toLong()) }
        }
        return md.digest()
    }

    /** Sprawdza rozpakowane obrazy (wg nazwy) z sumami SHA-256 zapisanymi w OTA. Zwraca liste bledow. */
    fun verify(input: Uri, inputName: String, images: Map<String, Uri>): List<String> {
        val (pfd, info) = openPayload(input, inputName)
        pfd.close()
        val total = info.parts.sumOf { it.size }
        var done = 0L
        val bad = ArrayList<String>()
        for (p in info.parts) {
            val u = images[p.name]
            when {
                u == null -> bad += tr("${p.name}: brak pliku", "${p.name}: file missing")
                p.hash == null -> log(tr("  ${p.name}: brak sumy w OTA - pominieto", "  ${p.name}: no checksum in OTA - skipped"))
                else -> {
                    val h = sha256(u) { n -> done += n; progress(done, total) }
                    if (h.contentEquals(p.hash)) log("  OK ${p.name}") else bad += tr("${p.name}: ZLA SUMA", "${p.name}: BAD CHECKSUM")
                }
            }
        }
        return bad
    }

    fun extract(input: Uri, inputName: String, outTree: Uri, threads: Int = 3): Result {
        val pfd = ctx.contentResolver.openFileDescriptor(input, "r") ?: throw java.io.IOException(tr("Nie mozna otworzyc $inputName", "Cannot open $inputName"))
        pfd.use {
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val base = if (inputName.endsWith(".zip", true)) {
                ZipLocator.findStored(ch, "payload.bin") ?: throw IllegalArgumentException(tr("W ZIP nie ma payload.bin", "No payload.bin in the ZIP"))
            } else 0L
            val info = PayloadParser.parse(ch, base)
            val superDef = if (inputName.endsWith(".zip", true)) try {
                ZipLocator.readEntry(ch) { n -> n.replace('\\', '/').contains("META/") && n.substringAfterLast('/').startsWith("super_def") && n.endsWith(".json") }
            } catch (_: Exception) { null } else null
            if (superDef != null) log(tr("OTA: znaleziono ${superDef.first} (uklad partycji super)", "OTA: found ${superDef.first} (super partition layout)"))
            log(tr("payload: ${info.parts.size} partycji, blok ${info.blockSize}, logicznych: ${info.dynamic.size}", "payload: ${info.parts.size} partitions, block ${info.blockSize}, logical: ${info.dynamic.size}"))
            for (p in info.parts) for (o in p.ops) {
                if (o.type !in setOf(OpType.REPLACE, OpType.REPLACE_BZ, OpType.REPLACE_XZ, OpType.ZSTD, OpType.ZERO, OpType.DISCARD))
                    throw IllegalArgumentException(tr("${p.name}: operacja typu ${o.type} - to OTA przyrostowe, potrzebne pelne OTA", "${p.name}: operation type ${o.type} - incremental OTA, a full OTA is required"))
            }

            val cr = ctx.contentResolver
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(outTree, DocumentsContract.getTreeDocumentId(outTree))
            val outName = inputName.substringBeforeLast('.').ifEmpty { "firmware" }
            val outDir = DocumentsContract.createDocument(cr, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, outName)
                ?: throw java.io.IOException(tr("Nie mozna utworzyc folderu $outName", "Cannot create folder $outName"))
            val groupDirs = HashMap<String, Uri>()
            for (g in listOf("BOOTLOADER", "CRITICAL", "EXTRA", "MODEM", "SYSTEM")) {
                groupDirs[g] = DocumentsContract.createDocument(cr, outDir, DocumentsContract.Document.MIME_TYPE_DIR, g)
                    ?: throw java.io.IOException(tr("Nie mozna utworzyc $g", "Cannot create $g"))
            }

            val total = info.parts.sumOf { it.size }
            val done = AtomicLong()
            val bad = ArrayList<String>()
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val futures = ArrayList<Future<*>>()
                for (p in info.parts.sortedByDescending { it.size }) {
                    futures += pool.submit(Runnable {
                        val g = Classifier.folder(p.name, info.dynamic)
                        val doc = DocumentsContract.createDocument(cr, groupDirs[g]!!, "application/octet-stream", "${p.name}.img")
                            ?: throw java.io.IOException(tr("Nie mozna utworzyc ${p.name}.img", "Cannot create ${p.name}.img"))
                        writePartition(ch, info, p, doc) { n -> progress(done.addAndGet(n), total) }
                        val ok = p.hash?.let { sha256(doc) {}.contentEquals(it) }
                        when (ok) {
                            true -> log(tr("  $g/${p.name}.img (${p.size / (1 shl 20)} MB) - suma OK", "  $g/${p.name}.img (${p.size / (1 shl 20)} MB) - checksum OK"))
                            false -> { synchronized(bad) { bad += p.name }; log(tr("  $g/${p.name}.img - ZLA SUMA SHA-256!", "  $g/${p.name}.img - BAD SHA-256 CHECKSUM!")) }
                            null -> log(tr("  $g/${p.name}.img (${p.size / (1 shl 20)} MB) - brak sumy w OTA", "  $g/${p.name}.img (${p.size / (1 shl 20)} MB) - no checksum in OTA"))
                        }
                    })
                }
                for (f in futures) try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
            } finally {
                pool.shutdownNow()
            }
            if (bad.isNotEmpty()) throw IllegalStateException(tr("Bledne sumy: ${bad.joinToString()} - NIE flashuj, rozpakuj ponownie", "Bad checksums: ${bad.joinToString()} - DO NOT flash, extract again"))
            if (superDef != null) try {
                DocumentsContract.createDocument(cr, outDir, "application/octet-stream", "super_def.json")?.let { u ->
                    cr.openOutputStream(u)?.use { it.write(superDef.second) }
                }
            } catch (_: Exception) {}
            // uklad super z OTA (grupy i rozmiary) - dla budowania super flashera
            if (info.groups.isNotEmpty()) try {
                val txt = info.groups.joinToString("\n") { g -> "${g.name} ${g.size} ${g.partitions.joinToString(",")}" } + "\n"
                DocumentsContract.createDocument(cr, outDir, "text/plain", "super_groups.txt")?.let { u ->
                    cr.openOutputStream(u)?.use { it.write(txt.toByteArray()) }
                }
            } catch (_: Exception) {}
            log(tr("Rozpakowano do: $outName - wszystkie sumy SHA-256 zgodne", "Extracted to: $outName - all SHA-256 checksums match"))
            return Result(outTree, DocumentsContract.getDocumentId(outDir))
        }
    }

    private fun writePartition(src: FileChannel, info: PayloadInfo, p: PartUpdate, doc: Uri, onBytes: (Long) -> Unit) {
        val bs = info.blockSize.toLong()
        val opfd = ctx.contentResolver.openFileDescriptor(doc, "rw") ?: throw java.io.IOException(tr("Zapis ${p.name} niemozliwy", "Cannot write ${p.name}"))
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
