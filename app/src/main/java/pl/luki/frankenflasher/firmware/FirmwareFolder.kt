package pl.luki.frankenflasher.firmware

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import pl.luki.frankenflasher.fastboot.ImageSource
import java.io.InputStream

/** Obraz firmware: z folderu SAF albo zwykly plik (Franken). */
class DocImage(override val name: String, override val size: Long, private val opener: () -> InputStream) : ImageSource {
    constructor(ctx: Context, uri: Uri, name: String, size: Long) :
        this(name, size, { ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Nie mozna otworzyc $name") })
    constructor(f: java.io.File) : this(f.name, f.length(), { f.inputStream() })
    override fun open(): InputStream = opener()
    val base get() = name.substringBeforeLast('.')
}

/** Obraz w pamieci (np. spatchowany vbmeta). */
class BytesImage(override val name: String, private val data: ByteArray) : ImageSource {
    override val size get() = data.size.toLong()
    override fun open(): InputStream = data.inputStream()
}

class FirmwareFolder(val groups: Map<String, List<DocImage>>) {
    operator fun get(g: String): List<DocImage> = groups[g].orEmpty()
    fun summary(): String = GROUPS.joinToString("\n") { g ->
        val l = this[g]
        if (l.isEmpty()) "$g: -" else "$g: ${l.size} (${l.sumOf { it.size } / (1 shl 20)} MB)"
    }

    companion object {
        val GROUPS = listOf("CRITICAL", "EXTRA", "MODEM", "BOOTLOADER", "SUPER", "SYSTEM")

        private class Child(val id: String, val name: String, val isDir: Boolean, val size: Long)

        private fun children(ctx: Context, tree: Uri, docId: String): List<Child> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val out = ArrayList<Child>()
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Child(
                        c.getString(0), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) 0 else c.getLong(3),
                    )
                }
            }
            return out
        }

        fun scan(ctx: Context, tree: Uri, rootId: String = DocumentsContract.getTreeDocumentId(tree)): FirmwareFolder {
            val groups = HashMap<String, List<DocImage>>()
            for (dir in children(ctx, tree, rootId).filter { it.isDir }) {
                val g = dir.name.uppercase()
                if (g !in GROUPS) continue
                groups[g] = children(ctx, tree, dir.id)
                    .filter { !it.isDir && it.name.endsWith(".img", true) && !it.name.startsWith("preloader", true) && it.size > 0 }
                    .map { DocImage(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, it.id), it.name, it.size) }
                    .sortedBy { it.name }
            }
            return FirmwareFolder(groups)
        }
    }
}
