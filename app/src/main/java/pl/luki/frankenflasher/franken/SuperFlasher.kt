package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.File
import java.io.IOException

/**
 * Super flasher z rozpakowanego OTA (folder BOOTLOADER/CRITICAL/EXTRA/MODEM/SYSTEM):
 * obrazy z SYSTEM -> SUPER/super.img (sparse, liblp) + skrypty flash_all dla PC (Windows/Linux).
 */
class SuperFlasher(private val log: (String) -> Unit, private val progress: (Long, Long) -> Unit = { _, _ -> }) {

    fun build(folder: File, superSize: Long): File {
        val sys = File(folder, "SYSTEM")
        val images = sys.listFiles { f -> f.isFile && f.name.endsWith(".img") && f.length() > 0 }.orEmpty()
            .associateBy { it.name.removeSuffix(".img") }
        if (images.isEmpty()) throw IOException(tr("Brak obrazow w ${sys.path}", "No images in ${sys.path}"))
        // grupa z OTA (super_groups.txt zapisany przy rozpakowaniu)
        var group = "qti_dynamic_partitions"
        var groupMax = superSize - (4L shl 20)
        val gf = File(folder, "super_groups.txt")
        if (gf.isFile) {
            val g = gf.readLines().map { it.trim().split(' ') }.filter { it.size >= 2 }.maxByOrNull { it[1].toLongOrNull() ?: 0 }
            if (g != null) { group = g[0].removeSuffix("_a").removeSuffix("_b"); g[1].toLongOrNull()?.takeIf { it > 0 }?.let { groupMax = it } }
            log(tr("super: grupa z OTA: $group (${groupMax / (1 shl 20)} MB)", "super: group from OTA: $group (${groupMax / (1 shl 20)} MB)"))
        } else log(tr("super: brak super_groups.txt - grupa $group, ${groupMax / (1 shl 20)} MB", "super: no super_groups.txt - group $group, ${groupMax / (1 shl 20)} MB"))
        if (groupMax > superSize) groupMax = superSize - (4L shl 20)
        val free = folder.usableSpace
        val need = images.values.sumOf { it.length() } + (64L shl 20)
        if (free < need) throw IOException(tr("Za malo miejsca: wolne ${free / (1 shl 30)} GB, potrzeba ${need / (1 shl 30) + 1} GB",
            "Not enough storage: free ${free / (1 shl 30)} GB, need ${need / (1 shl 30) + 1} GB"))
        val out = File(File(folder, "SUPER"), "super.img")
        val tmp = File(out.parentFile, "super.img.part")
        SuperBuilder(log, progress).build(images, group, groupMax, superSize, tmp)
        out.delete(); if (!tmp.renameTo(out)) throw IOException("rename ${tmp.name}")
        writeScripts(folder)
        log(tr("super.img gotowy: ${out.length() / (1 shl 20)} MB (sparse) + skrypty flash_all.bat / flash_all.sh",
            "super.img ready: ${out.length() / (1 shl 20)} MB (sparse) + scripts flash_all.bat / flash_all.sh"))
        return out
    }

    private fun writeScripts(folder: File) {
        val groups = listOf("CRITICAL", "EXTRA", "MODEM", "BOOTLOADER")
        val imgs = groups.flatMap { g -> File(folder, g).listFiles { f -> f.name.endsWith(".img") && f.length() > 0 }.orEmpty().sortedBy { it.name }.map { g to it.name } }
            .sortedBy { (g, n) -> if (g == "BOOTLOADER" && n.startsWith("vbmeta")) 1 else 0 }
        val bat = StringBuilder()
        bat.append("@echo off\r\ncd /d \"%~dp0\"\r\nset FB=fastboot\r\nif exist platform-tools\\fastboot.exe set FB=platform-tools\\fastboot.exe\r\n")
        bat.append("echo Super flasher - telefon w trybie BOOTLOADER (fastboot), nie fastbootd\r\n%FB% devices\r\npause\r\n")
        for ((g, n) in imgs) bat.append("%FB% flash --slot=all ${n.removeSuffix(".img")} \"$g\\$n\" || goto err\r\n")
        bat.append("%FB% flash super \"SUPER\\super.img\" || goto err\r\n%FB% --set-active=a\r\n")
        bat.append("set /p W=Wyczyscic dane / wipe data? (t/n): \r\nif /i \"%W%\"==\"t\" %FB% -w\r\nif /i \"%W%\"==\"y\" %FB% -w\r\n")
        bat.append("%FB% reboot\r\necho OK\r\npause\r\nexit /b 0\r\n:err\r\necho BLAD / ERROR\r\npause\r\nexit /b 1\r\n")
        File(folder, "flash_all.bat").writeText(bat.toString())
        val sh = StringBuilder("#!/bin/sh\nset -e\ncd \"$(dirname \"$0\")\"\nFB=fastboot\n[ -x platform-tools/fastboot ] && FB=platform-tools/fastboot\n")
        sh.append("echo 'Super flasher - telefon w trybie BOOTLOADER (fastboot), nie fastbootd'\n\$FB devices\n")
        for ((g, n) in imgs) sh.append("\$FB flash --slot=all ${n.removeSuffix(".img")} \"$g/$n\"\n")
        sh.append("\$FB flash super SUPER/super.img\n\$FB --set-active=a\nprintf 'Wipe data? (y/n): '; read W\n[ \"\$W\" = y ] || [ \"\$W\" = t ] && \$FB -w\n\$FB reboot\necho OK\n")
        File(folder, "flash_all.sh").apply { writeText(sh.toString()); setExecutable(true) }
    }
}
