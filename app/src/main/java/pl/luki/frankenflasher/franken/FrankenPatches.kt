package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.File

/** Poprawki build.prop (odpowiedniki metod ErofsImagePatcher z FFF, z wyjatkiem min_settings z v5). */
object PropPatches {
    private val DISPLAY_PREFIXES = listOf(
        "ro.oplus.display.", "persist.oplus.display.", "ro.display.", "ro.density.", "ro.sf.lcd", "ro.oplus.density.",
        "ro.oplus.resolution.", "ro.oplus.refreshrate.", "sys.oplus.display.", "debug.sf.", "ro.oplus.lcd.", "ro.lcd.",
        "persist.brightness.", "persist.lowbrightnessthreshold", "ro.vendor.display.", "vendor.display.", "persist.vendor.display.",
    )
    private val BT_PREFIXES = listOf(
        "bluetooth.", "ro.bluetooth.", "persist.vendor.bluetooth.", "persist.sys.bluetooth.", "persist.bluetooth.",
        "persist.sys.bt.", "persist.sys.oplus.bt.",
    )
    private val INHERITED_KEY_CONTAINS = listOf("a2dp", "audio")
    private val DISPLAY_KEY_NEEDLES = listOf("display", "screen", "lcd", "panel", "backlight", "brightness", "luminance", "dimming", "hbm", "dbv")
    private val REMOVABLE_HW_NEEDLES = listOf("esim", "wireless_charg", "wirelesscharg", "reverse_wireless")
    private val FULL_REPLACE_NEEDLES = listOf(
        "bluetooth", ".bt.", "bdroid", "a2dp", "avrcp", "hfp", "leaudio", "le_audio", "lhdc", "aptx", "ldac",
        "camera", "audio", "nfc", "wifi", "wlan", "wfd", "radio", "telephony", "fusionlight",
    )

    fun keyOf(line: String): String {
        val s = line.trimStart(); val i = s.indexOf('=')
        return (if (i < 0) s else s.substring(0, i)).trimEnd()
    }

    private fun isProp(line: String): Boolean {
        val s = line.trimStart()
        return s.isNotEmpty() && s[0] != '#' && s.contains('=')
    }

    private fun containsAny(s: String, needles: List<String>) = needles.any { s.contains(it, ignoreCase = true) }

    fun isInheritedProp(line: String): Boolean {
        if (line.contains("min_settings")) return false // v5: crash ustawien jasnosci
        if (DISPLAY_PREFIXES.any { line.startsWith(it) } || BT_PREFIXES.any { line.startsWith(it) }) return true
        val i = line.indexOf('='); val key = if (i < 0) line else line.substring(0, i)
        return containsAny(key, INHERITED_KEY_CONTAINS) || containsAny(key, REMOVABLE_HW_NEEDLES) || containsAny(key, FULL_REPLACE_NEEDLES)
    }

    fun isHardwareTiedKey(key: String): Boolean =
        DISPLAY_PREFIXES.any { key.startsWith(it) } || BT_PREFIXES.any { key.startsWith(it) } ||
            containsAny(key, DISPLAY_KEY_NEEDLES) || containsAny(key, REMOVABLE_HW_NEEDLES) || containsAny(key, FULL_REPLACE_NEEDLES)

    /** PatchDisplayProps: sprzetowe wlasciwosci (ekran, BT, audio, kamera, wifi...) z bazy. Zwraca liczbe zmian. */
    fun patchDisplayProps(donor: MutableList<String>, base: List<String>): Int {
        val inherited = base.filter { isInheritedProp(it) && it.contains('=') }
        val baseKeys = inherited.map { keyOf(it) }.toHashSet()
        val before = donor.toList()
        val r1 = donor.removeAll { isInheritedProp(it) && keyOf(it) in baseKeys }
        val baseAll = base.filter { it.contains('=') }.map { keyOf(it) }.toHashSet()
        val r2 = donor.removeAll { isProp(it) && keyOf(it).let { k -> isHardwareTiedKey(k) && k !in baseAll } }
        if (!r1 && !r2 && inherited.isEmpty()) return 0
        if (donor.isNotEmpty() && donor.last().isNotEmpty()) donor.add("")
        donor.addAll(inherited)
        val seen = HashSet<String>()
        for (i in donor.indices.reversed()) {
            val s = donor[i].trimStart()
            if (s.isEmpty() || s[0] == '#' || !s.contains('=')) continue
            if (!seen.add(keyOf(donor[i]))) donor.removeAt(i)
        }
        return if (donor == before) 0 else maxOf(1, inherited.size)
    }

    fun syncActionButton(donor: MutableList<String>, base: List<String>, log: (String) -> Unit): Boolean {
        val key = "ro.oplus.key.actionbutton"
        var baseVal: String? = null
        for (l in base) {
            val s = l.trim(); val i = s.indexOf('=')
            if (s.isNotEmpty() && s[0] != '#' && i > 0 && s.substring(0, i).trim() == key) baseVal = s.substring(i + 1).trim()
        }
        val prefix = "$key="
        if (baseVal == null) {
            val removed = donor.removeAll { it.trimStart().startsWith(prefix) }
            if (removed) log(tr("my_product: $key usuniety (brak w bazie)", "my_product: $key removed (not in base)"))
            return removed
        }
        val want = "$key=$baseVal"
        var found = false; var changed = false
        var i = 0
        while (i < donor.size) {
            if (donor[i].trimStart().startsWith(prefix)) {
                if (!found) {
                    found = true
                    if (donor[i].trimEnd() != want) { donor[i] = want; changed = true }
                } else { donor.removeAt(i); changed = true; continue }
            }
            i++
        }
        if (!found) { donor.add(want); changed = true }
        if (changed) log(tr("my_product: $want (z bazy)", "my_product: $want (from base)"))
        return changed
    }

    private fun forceLineValue(lines: MutableList<String>, key: String, value: String, addIfMissing: Boolean, log: (String) -> Unit): Boolean {
        val prefix = "$key="; val want = "$key=$value"
        var found = false; var changed = false
        for (i in lines.indices) if (lines[i].trimStart().startsWith(prefix)) {
            found = true
            if (lines[i] != want) { lines[i] = want; changed = true }
        }
        if (!found && addIfMissing) { lines.add(want); changed = true }
        if (changed) log("my_product: $key = $value")
        return changed
    }

    fun forceProductBuildProps(lines: MutableList<String>, log: (String) -> Unit): Boolean {
        val a = forceLineValue(lines, "ro.oplus.audio.effect.speaker_geq", "false", true, log)
        val b = forceLineValue(lines, "ro.oplus.fusionlight", "true", false, log)
        return a || b
    }

    fun dedupKeys(lines: MutableList<String>): Int {
        val seen = HashSet<String>(); var n = 0
        for (i in lines.indices.reversed()) {
            val s = lines[i].trimStart()
            if (s.isEmpty() || s[0] == '#') continue
            val eq = s.indexOf('=')
            if (eq <= 0) continue
            if (!seen.add(s.substring(0, eq).trimEnd())) { lines.removeAt(i); n++ }
        }
        return n
    }
}

/** Poprawki plikow funkcji (etc/permissions, etc/extension). */
object Features {
    private val DEVICE_FEATURE_NEEDLES = listOf(
        "bluetooth", ".bt.", "a2dp", "avrcp", "leaudio", "le_audio", ".display.", "camera", "rgbnormalize", "resolution",
        "refresh_rate", "high_refresh", "esim", "wireless_charg", "wirelesscharg", "reverse_wireless",
    )
    /** Dodatek: funkcje wibracji tez z bazy (usuwa vibrator_qcom_lmvibrator dawcy GT 8 Pro). */
    private val VIBRATION_NEEDLES = listOf("vibrat", "haptic")
    private val PRESERVED = setOf("oplus.hardware.vibrator_style_switch")
    private val TO_REMOVE = listOf("oplus.software.vibrator_luxunvibrator", "oplus.software.display.origin_roundcorner_support")
    private val DIRS = listOf("etc/permissions", "etc/extension")

    val REQUIRED_APP_FEATURES = listOf(
        "com.oplus.wallpapers.3d_wallpaper" to "    <app_feature name=\"com.oplus.wallpapers.3d_wallpaper\" args=\"boolean:true\"/>",
        "com.oplus.aiplaymate.support" to "    <app_feature name=\"com.oplus.aiplaymate.support\">\n        <StringList args=\"pubg_1.0\"/>\n        <StringList args=\"mlbb_1.0\"/>\n        <StringList args=\"wzry_3.0\"/>\n        <StringList args=\"wzry_4.0\"/>\n        <StringList args=\"jcc_1.0\"/>\n    </app_feature>",
        "os.graphic.gallery.photoeditor.ailighting" to "    <app_feature name=\"os.graphic.gallery.photoeditor.ailighting\" args=\"boolean:true\"/>",
        "com.oplus.aiwriter.input_entrance_enabled" to "    <app_feature name=\"com.oplus.aiwriter.input_entrance_enabled\" args=\"boolean:true\"/>",
        "os.graphic.gallery.photoeditor.olive.slow_motion" to "    <app_feature name=\"os.graphic.gallery.photoeditor.olive.slow_motion\" args=\"boolean:true\"/>",
        "com.oplus.dmp.aiask_enable" to "    <app_feature name=\"com.oplus.dmp.aiask_enable\" args=\"int:1\"/>",
        "com.oplus.gallery3d.aihd_support" to "    <app_feature name=\"com.oplus.gallery3d.aihd_support\"/>",
        "com.oplus.wallpapers.wallpaper_matting" to "    <app_feature name=\"com.oplus.wallpapers.wallpaper_matting\" args=\"int:2\"/>",
        "com.oplus.wallpapers.wallpaper_blur" to "    <app_feature name=\"com.oplus.wallpapers.wallpaper_blur\" args=\"int:1\"/>",
        "os.graphic.gallery.collage.asset_bounds_break" to "    <app_feature name=\"os.graphic.gallery.collage.asset_bounds_break\" args=\"boolean:true\"/>",
        "com.android.settings_dark_mode_style_enable" to "    <app_feature name=\"com.android.settings_dark_mode_style_enable\" args=\"boolean:true\"/>",
    )
    private val BASE_CONDITIONAL = listOf("com.oplus.eyeprotect.ai_intelligent_eye_protect_support")

    private fun xmls(root: File, dir: String) = File(root, dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }

    private fun nameOf(line: String): String {
        val i = line.indexOf("name=\"", ignoreCase = true)
        if (i < 0) return ""
        val j = line.indexOf('"', i + 6)
        return if (j < 0) "" else line.substring(i + 6, j)
    }

    fun transferDeviceFeatures(t: Franken.Tree, fixVibration: Boolean, log: (String) -> Unit) {
        val needles = if (fixVibration) DEVICE_FEATURE_NEEDLES + VIBRATION_NEEDLES else DEVICE_FEATURE_NEEDLES
        fun isTarget(line: String): Boolean {
            val s = line.trimStart()
            return s.startsWith("<oplus-feature", ignoreCase = true) && needles.any { s.contains(it, ignoreCase = true) }
        }
        val baseNames = HashSet<String>()
        for (d in DIRS) for (f in xmls(t.baseRoot, d)) f.readLines().filter { isTarget(it) }.forEach { baseNames += nameOf(it).lowercase() }
        var added = 0; var removed = 0
        for (d in DIRS) for (f in xmls(t.donorRoot, d)) {
            val bf = File(File(t.baseRoot, d), f.name)
            val baseLines = if (bf.isFile) bf.readLines().filter { isTarget(it) } else emptyList()
            val lines = f.readLines().toMutableList()
            val orig = lines.toList()
            if (baseLines.isNotEmpty()) {
                val preserved = lines.filter { isTarget(it) && nameOf(it) in PRESERVED && nameOf(it).lowercase() !in baseNames }
                val gone = lines.filter { isTarget(it) }.map { nameOf(it) }.filter { it.lowercase() !in baseNames && it !in PRESERVED }
                lines.removeAll { isTarget(it) }
                var at = lines.indexOfLast { it.trimStart().startsWith("</oplus-config>", ignoreCase = true) }
                if (at < 0) at = lines.size
                lines.addAll(at, baseLines + preserved)
                added += baseLines.size
                if (gone.isNotEmpty()) log(tr("funkcje: ${f.name} - usuniete (brak w bazie): ${gone.joinToString()}", "features: ${f.name} - removed (not in base): ${gone.joinToString()}"))
            } else {
                val gone = lines.filter { isTarget(it) && nameOf(it).lowercase() !in baseNames && nameOf(it) !in PRESERVED }
                if (gone.isNotEmpty()) {
                    lines.removeAll { it in gone }
                    removed += gone.size
                    log(tr("funkcje: ${f.name} - usuniete (brak w bazie): ${gone.joinToString { nameOf(it) }}", "features: ${f.name} - removed (not in base): ${gone.joinToString { nameOf(it) }}"))
                }
            }
            if (lines != orig) t.writeText("$d/${f.name}", lines.joinToString("\n") + "\n")
        }
        log(tr("funkcje sprzetowe z bazy: przeniesiono $added, usunieto $removed", "hardware features from base: transferred $added, removed $removed") + if (fixVibration) tr(" (z wibracjami)", " (incl. vibration)") else "")
    }

    fun removeBlacklisted(t: Franken.Tree) {
        val needles = TO_REMOVE.map { "name=\"$it\"" }
        for (d in DIRS) for (f in xmls(t.donorRoot, d)) {
            val lines = f.readLines().toMutableList()
            if (lines.removeAll { l -> l.trimStart().startsWith("<oplus-feature", true) && needles.any { l.contains(it) } }) {
                t.writeText("$d/${f.name}", lines.joinToString("\n") + "\n")
            }
        }
    }

    fun ensureAppFeatures(t: Franken.Tree): Int {
        val rel = "etc/extension/com.oplus.app-features.xml"
        val f = File(t.donorRoot, rel)
        if (f.isFile) {
            val text = f.readText()
            val missing = REQUIRED_APP_FEATURES.filter { !text.contains("name=\"${it.first}\"") }
            if (missing.isEmpty()) return 0
            val block = missing.joinToString("\n") { it.second } + "\n"
            val i = text.lastIndexOf("</")
            val nt = if (i >= 0) text.substring(0, i) + block + text.substring(i) else text.trimEnd() + "\n" + block
            t.writeText(rel, nt)
            return missing.size
        }
        val body = REQUIRED_APP_FEATURES.joinToString("\n") { it.second }
        t.writeText(rel, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n\n<extension_config>\n$body\n</extension_config>\n")
        return REQUIRED_APP_FEATURES.size
    }

    fun removeAppFeaturesMissingInBase(t: Franken.Tree, log: (String) -> Unit) {
        var n = 0
        for (name in BASE_CONDITIONAL) {
            val marker = "name=\"$name\""
            if (DIRS.any { d -> xmls(t.baseRoot, d).any { it.readText().contains(marker) } }) continue
            val rx = Regex("""[ \t]*<app_feature\b[^>]*""" + Regex.escape(marker) + """[^>]*?(/>|>.*?</app_feature>)[ \t]*\r?\n?""", RegexOption.DOT_MATCHES_ALL)
            for (d in DIRS) for (f in xmls(t.donorRoot, d)) {
                val text = f.readText()
                if (!text.contains(marker)) continue
                val nt = rx.replace(text, "")
                if (nt != text) { t.writeText("$d/${f.name}", nt); n++ }
            }
        }
        if (n > 0) log(tr("my_product: app-features - usunieto $n (brak w bazie)", "my_product: app-features - removed $n (not in base)"))
    }
}
