package pl.luki.frankenflasher.franken

import java.io.File
import java.io.IOException

/** Natywne erofs-utils (budowane w CI ze zrodel) - na telefonie jako lib*.so w nativeLibraryDir. */
class ErofsTools(val mkfs: File, val fsck: File, val dump: File, private val log: (String) -> Unit) {

    fun available() = mkfs.canExecute() && fsck.canExecute()

    /** Uruchamia narzedzie, przekazuje wyjscie do logu (max [maxLines] linii), zwraca kod wyjscia. */
    fun run(vararg args: String, maxLines: Int = 40): Int {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        var n = 0
        p.inputStream.bufferedReader().forEachLine { if (n++ < maxLines) log("  $it") }
        if (n > maxLines) log("  ... (${n - maxLines} linii pominieto)")
        return p.waitFor()
    }

    /** Wypakowuje obraz EROFS do katalogu (bez wlascicieli - aplikacja nie jest rootem). */
    fun extract(img: File, out: File): Int {
        out.deleteRecursively()
        out.mkdirs()
        return run(fsck.path, "--extract=${out.path}", "--no-preserve", "--force", "--overwrite", img.path)
    }

    fun extractOrThrow(img: File, out: File) {
        val rc = extract(img, out)
        if (rc != 0) throw IOException("fsck.erofs --extract ${img.name}: kod $rc")
    }

    /** Zawartosc jednego pliku z obrazu (dump.erofs --cat) - bez wypakowania calego obrazu. */
    fun cat(img: File, path: String): ByteArray {
        val p = ProcessBuilder(dump.path, "--cat", "--path=/" + path.trimStart('/'), img.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes()
        val rc = p.waitFor()
        if (rc != 0) throw IOException("dump.erofs --cat $path: kod $rc")
        return out
    }

    /** fsck obrazu: 0 = OK. */
    fun check(img: File): Int = run(fsck.path, img.path)
}
