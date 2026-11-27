package pl.luki.frankenflasher.franken

import android.content.Context
import java.io.File

fun erofsTools(ctx: Context, log: (String) -> Unit): ErofsTools {
    val dir = ctx.applicationInfo.nativeLibraryDir
    val bins = listOf("libmkfs_erofs.so", "libfsck_erofs.so", "libdump_erofs.so").map { File(dir, it) }
    // Wbudowana w system kopia z Frankena < 2.3.1 miala biblioteki bez prawa wykonania (0644).
    if (bins.any { it.isFile && !it.canExecute() }) throw java.io.IOException(pl.luki.frankenflasher.tr(
        "Narzedzia erofs w $dir nie maja prawa wykonania (Franken Flasher wbudowany w system starsza wersja). " +
            "Zainstaluj APK Franken Flashera jako aktualizacje (np. ten sam plik ponownie) - wtedy dziala z /data/app.",
        "erofs tools in $dir are not executable (Franken Flasher embedded in the system by an older version). " +
            "Install the Franken Flasher APK as an update (e.g. the same file again) - it then runs from /data/app."))
    return ErofsTools(bins[0], bins[1], bins[2], log, File(dir, "libsecilc.so").takeIf { it.isFile })
}
