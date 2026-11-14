package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.File
import java.io.IOException

/**
 * Usuwanie aplikacji systemowych z obrazow EROFS (debloat) przy budowaniu super flashera / Frankena.
 * Klucz wyboru: "obraz:sciezka", np. "my_heytap:app/GoogleContacts".
 */
class Debloat(private val log: (String) -> Unit, private val check: ((File) -> Int)? = null) {

    class App(val image: String, val path: String, val size: Long) {
        val key get() = "$image:$path"
        val name get() = path.substringAfterLast('/')
    }

    companion object {
        /** Katalogi z aplikacjami (tez wariant system-as-root w system.img). */
        val ROOTS = listOf("app", "priv-app", "del-app", "del-app-pre", "reserve", "system/app", "system/priv-app")
    }

    fun scan(images: Map<String, File>): List<App> {
        val out = ArrayList<App>()
        for ((img, f) in images.toSortedMap()) {
            try {
                ErofsPatcher(f, readOnly = true).use { p ->
                    for (root in ROOTS) {
                        val l = p.list(root) ?: continue
                        for (d in l.filter { it.type == 2 }) {
                            val path = "$root/${d.name}"
                            out += App(img, path, dirSize(p, path, 3))
                        }
                    }
                }
            } catch (e: Exception) { log(tr("debloat: pomijam $img (${e.message})", "debloat: skipping $img (${e.message})")) }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun dirSize(p: ErofsPatcher, path: String, depth: Int): Long {
        val l = p.list(path) ?: return 0
        var s = 0L
        for (e in l) {
            if (e.type == 1) s += p.inode(e.nid).size
            else if (e.type == 2 && depth > 0) s += dirSize(p, "$path/${e.name}", depth - 1)
        }
        return s
    }

    /**
     * Usuwa wybrane katalogi aplikacji. Obrazy z wyborem sa kopiowane do [workDir] (oryginaly bez zmian).
     * Zwraca mape nazwa -> obraz (podmienione albo oryginalne).
     */
    fun apply(images: Map<String, File>, remove: Set<String>, workDir: File, step: (String) -> Unit = {}): Map<String, File> {
        if (remove.isEmpty()) return images
        val res = LinkedHashMap(images)
        workDir.mkdirs()
        var total = 0
        for ((img, f) in images) {
            val paths = remove.filter { it.startsWith("$img:") }.map { it.substringAfter(':') }
            if (paths.isEmpty()) continue
            val present = ErofsPatcher(f, readOnly = true).use { p -> paths.filter { p.exists(it) } }
            if (present.isEmpty()) continue
            step(tr("debloat: $img", "debloat: $img"))
            val out = File(workDir, "$img.img")
            if (out.canonicalPath != f.canonicalPath) {
                if (workDir.usableSpace < f.length() + (64L shl 20)) throw IOException(tr("debloat: za malo miejsca na kopie $img", "debloat: not enough space to copy $img"))
                f.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o, 4 shl 20) } }
            }
            ErofsPatcher(out).use { p ->
                for (path in present) p.delete(path)
                p.commit()
            }
            check?.let { c -> val rc = c(out); if (rc != 0) throw IOException(tr("fsck.erofs $img po debloat: kod $rc", "fsck.erofs $img after debloat: code $rc")) }
            log(tr("debloat: $img - usunieto ${present.size}: ", "debloat: $img - removed ${present.size}: ") + present.joinToString { it.substringAfterLast('/') })
            total += present.size
            res[img] = out
        }
        if (total > 0) log(tr("debloat: razem usunieto $total aplikacji", "debloat: removed $total apps in total"))
        return res
    }
}
