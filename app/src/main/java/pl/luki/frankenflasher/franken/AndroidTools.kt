package pl.luki.frankenflasher.franken

import android.content.Context
import java.io.File

fun erofsTools(ctx: Context, log: (String) -> Unit) = ErofsTools(
    File(ctx.applicationInfo.nativeLibraryDir, "libmkfs_erofs.so"),
    File(ctx.applicationInfo.nativeLibraryDir, "libfsck_erofs.so"),
    File(ctx.applicationInfo.nativeLibraryDir, "libdump_erofs.so"),
    log,
)
