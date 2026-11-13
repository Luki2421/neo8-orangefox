package pl.luki.frankenflasher.franken

import android.content.Context
import java.io.File

/** Natywne erofs-utils (budowane w CI ze zrodel) - wypakowane jako lib*.so w nativeLibraryDir. */
class ErofsTools(ctx: Context, private val log: (String) -> Unit) {
    private val dir = File(ctx.applicationInfo.nativeLibraryDir)
    val mkfs = File(dir, "libmkfs_erofs.so")
    val fsck = File(dir, "libfsck_erofs.so")

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
        out.mkdirs()
        return run(fsck.path, "--extract=${out.path}", "--no-preserve", "--force", img.path)
    }
}
