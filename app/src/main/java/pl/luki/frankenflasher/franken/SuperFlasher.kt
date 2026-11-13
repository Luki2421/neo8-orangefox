package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.File
import java.io.IOException

/**
 * Super flasher z rozpakowanego OTA (folder BOOTLOADER/CRITICAL/EXTRA/MODEM/SYSTEM):
 * obrazy z SYSTEM -> SUPER/super.img (sparse, liblp) + skrypty flash_all dla PC (Windows/Linux).
 */
class SuperFlasher(private val log: (String) -> Unit, private val progress: (Long, Long) -> Unit = { _, _ -> }) {

    class Def(val superSize: Long, val group: String, val groupMax: Long)

    companion object {
        /** META/super_def.json z OTA (jak "Parse" w Super Builderze FFF): rozmiar super i grupa. */
        fun parseDef(json: String): Def? {
            fun objects(arrayKey: String): List<String> {
                val i = json.indexOf("\"$arrayKey\"")
                if (i < 0) return emptyList()
                val a = json.indexOf('[', i); if (a < 0) return emptyList()
                val out = ArrayList<String>(); var depth = 0; var start = -1
                for (k in a + 1 until json.length) {
                    when (json[k]) {
                        '{' -> { if (depth == 0) start = k; depth++ }
                        '}' -> { depth--; if (depth == 0 && start >= 0) out += json.substring(start, k + 1) }
                        ']' -> if (depth == 0) return out
                    }
                }
                return out
            }
            fun field(o: String, k: String) = Regex("\"$k\"\\s*:\\s*\"?([^\",}]+)").find(o)?.groupValues?.get(1)?.trim()
            val size = objects("block_devices").firstOrNull { field(it, "name") == "super" }?.let { field(it, "size")?.toLongOrNull() }
                ?: return null
            val g = objects("groups").map { (field(it, "name") ?: "") to (field(it, "maximum_size")?.toLongOrNull() ?: 0L) }
                .filter { it.first.isNotEmpty() && it.first != "default" }.maxByOrNull { it.second }
            val name = (g?.first ?: "qti_dynamic_partitions").removeSuffix("_a").removeSuffix("_b")
            return Def(size, name, g?.second?.takeIf { it > 0 } ?: (size - (4L shl 20)))
        }
    }

    class Setup(val images: Map<String, File>, val superSize: Long, val group: String, val groupMax: Long)

    fun setup(folder: File, superSizeIn: Long?): Setup {
        val def = File(folder, "super_def.json").takeIf { it.isFile }?.let { parseDef(it.readText()) }
        val gfile = File(folder, "super_groups.txt")
        val payloadGroup = if (gfile.isFile) gfile.readLines().map { it.trim().split(' ') }.filter { it.size >= 2 }
            .maxByOrNull { it[1].toLongOrNull() ?: 0 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } else null
        val superSize = def?.superSize ?: superSizeIn
            ?: payloadGroup?.second?.takeIf { it > 0 }?.let { it + (4L shl 20) }?.also {
                log(tr("super: rozmiar wyliczony z payload: grupa ${payloadGroup.first} + 4 MB = $it B", "super: size derived from payload: group ${payloadGroup.first} + 4 MB = $it B"))
            }
            ?: throw IOException(tr("Brak super_def.json/super_groups.txt w folderze i nie podano rozmiaru super", "No super_def.json/super_groups.txt in the folder and no super size given"))
        if (def != null) log(tr("super_def.json: super ${def.superSize} B, grupa ${def.group} ${def.groupMax} B", "super_def.json: super ${def.superSize} B, group ${def.group} ${def.groupMax} B"))
        val sys = File(folder, "SYSTEM")
        val images = sys.listFiles { f -> f.isFile && f.name.endsWith(".img") && f.length() > 0 }.orEmpty()
            .associateBy { it.name.removeSuffix(".img") }
        if (images.isEmpty()) throw IOException(tr("Brak obrazow w ${sys.path}", "No images in ${sys.path}"))
        var group = def?.group ?: "qti_dynamic_partitions"
        var groupMax = def?.groupMax ?: (superSize - (4L shl 20))
        val gf = File(folder, "super_groups.txt")
        if (def == null && gf.isFile) {
            val g = gf.readLines().map { it.trim().split(' ') }.filter { it.size >= 2 }.maxByOrNull { it[1].toLongOrNull() ?: 0 }
            if (g != null) { group = g[0].removeSuffix("_a").removeSuffix("_b"); g[1].toLongOrNull()?.takeIf { it > 0 }?.let { groupMax = it } }
        }
        if (groupMax > superSize) groupMax = superSize - (4L shl 20)
        return Setup(images, superSize, group, groupMax)
    }

    fun build(folder: File, superSizeIn: Long?): File {
        val st = setup(folder, superSizeIn)
        val images = st.images; val group = st.group; val groupMax = st.groupMax; val superSize = st.superSize
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
        bat.append("echo Ostatni etap w fastbootd / last stage in fastbootd\r\n%FB% reboot fastboot\r\n")
        bat.append("%FB% flash super \"SUPER\\super.img\" || goto err\r\n%FB% --set-active=a\r\n")
        bat.append("set /p W=Wyczyscic dane / wipe data? (t/n): \r\nif /i \"%W%\"==\"t\" %FB% -w\r\nif /i \"%W%\"==\"y\" %FB% -w\r\n")
        bat.append("%FB% reboot\r\necho OK\r\npause\r\nexit /b 0\r\n:err\r\necho BLAD / ERROR\r\npause\r\nexit /b 1\r\n")
        File(folder, "flash_all.bat").writeText(bat.toString())
        val sh = StringBuilder("#!/bin/sh\nset -e\ncd \"$(dirname \"$0\")\"\nFB=fastboot\n[ -x platform-tools/fastboot ] && FB=platform-tools/fastboot\n")
        sh.append("echo 'Super flasher - telefon w trybie BOOTLOADER (fastboot), nie fastbootd'\n\$FB devices\n")
        for ((g, n) in imgs) sh.append("\$FB flash --slot=all ${n.removeSuffix(".img")} \"$g/$n\"\n")
        sh.append("echo 'Ostatni etap w fastbootd / last stage in fastbootd'\n\$FB reboot fastboot\n")
        sh.append("\$FB flash super SUPER/super.img\n\$FB --set-active=a\nprintf 'Wipe data? (y/n): '; read W\n[ \"\$W\" = y ] || [ \"\$W\" = t ] && \$FB -w\n\$FB reboot\necho OK\n")
        File(folder, "flash_all.sh").apply { writeText(sh.toString()); setExecutable(true) }
    }
}
