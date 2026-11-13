package pl.luki.frankenflasher.franken

import pl.luki.frankenflasher.tr
import java.io.File
import java.io.IOException

/**
 * Franken jak w FastbootFirmwareFlasher (z poprawkami v6):
 *  - partycje fizyczne (CRITICAL/EXTRA/MODEM/BOOTLOADER) i sprzetowe logiczne z BAZY,
 *  - system z DAWCY,
 *  - my_product dawcy poprawiony konfiguracja sprzetowa bazy,
 *  - my_manifest bazy z wersja dawcy,
 *  - app_v2 (Telefon, Kontakty, ...) odblokowane w my_stock / my_region dawcy.
 * Obrazy sa edytowane przez [ErofsPatcher] (etykiety SELinux zostaja).
 */
class Franken(
    private val baseDir: File,
    private val donorDir: File,
    private val outDir: File,
    /** Katalog roboczy na wypakowane drzewa - pamiec wewnetrzna aplikacji (obsluguje symlinki). */
    private val work: File,
    private val tools: ErofsTools,
    private val log: (String) -> Unit,
    private val step: (String) -> Unit = {},
) {
    class Options(
        var patchMyProduct: Boolean = true,
        var patchMyManifest: Boolean = true,
        var patchOdmIdentity: Boolean = true,
        var unlockAppV2: Boolean = true,
        var fixVibration: Boolean = true,
        var displayOverlayFromBase: Boolean = true,
        var skipMyPreload: Boolean = false,
        /** Dzwonki z my_product/res/audio/haptic_ringtone do product/media/audio (jak modul franken_ringtones). */
        var ringtones: Boolean = true,
        /** Partycje logiczne brane z bazy (domyslnie jak FFF: odm, odm_dlkm, vendor, vendor_dlkm, system_dlkm). */
        var fromBase: MutableSet<String> = HW_FROM_BASE.toMutableSet(),
    )

    /** Wynik: obraz -> plik (SYSTEM z bazy/dawcy/poprawiony), grupy fizyczne z bazy. */
    class Result(val groups: Map<String, List<File>>)

    fun build(opt: Options = Options()): Result {
        val baseSys = File(baseDir, "SYSTEM")
        val donorSys = File(donorDir, "SYSTEM")
        if (!baseSys.isDirectory) throw IOException(tr("Brak folderu SYSTEM w bazie: $baseSys", "No SYSTEM folder in base: $baseSys"))
        if (!donorSys.isDirectory) throw IOException(tr("Brak folderu SYSTEM w dawcy: $donorSys", "No SYSTEM folder in donor: $donorSys"))
        outDir.mkdirs(); work.deleteRecursively(); work.mkdirs()
        val outSys = File(outDir, "SYSTEM").apply { mkdirs() }

        // --- wybor partycji (FrankenBaseHardwareSystem) ---
        val donorImgs = donorSys.listFiles { f -> f.name.endsWith(".img", true) }.orEmpty().associateBy { it.name.removeSuffix(".img") }
        val baseImgs = baseSys.listFiles { f -> f.name.endsWith(".img", true) }.orEmpty().associateBy { it.name.removeSuffix(".img") }
        val chosen = LinkedHashMap<String, File>()
        val hw = opt.fromBase
        for ((n, f) in donorImgs.toSortedMap()) {
            if (n in hw) continue
            if (opt.skipMyPreload && n == "my_preload") continue
            chosen[n] = f
        }
        val missing = ArrayList<String>()
        for (n in hw.sorted()) {
            val b = baseImgs[n]
            if (b != null) chosen[n] = b else if (donorImgs.containsKey(n)) missing += n
        }
        log(tr("Franken: z BAZY: ", "Franken: from BASE: ") + hw.sorted().filter { baseImgs.containsKey(it) }.joinToString() + tr(", z DAWCY: ", ", from DONOR: ") + donorImgs.keys.count { it !in hw })
        if (missing.isNotEmpty()) log(tr("Franken: UWAGA - brak w bazie: ${missing.joinToString()} (nie beda wgrane)", "Franken: WARNING - missing in base: ${missing.joinToString()} (will not be flashed)"))
        baseImgs["my_manifest"]?.let { chosen["my_manifest"] = it }
        // Jak "Flash base firmware first" w FFF: partycje, ktorych dawca nie ma, zostaja z bazy
        val baseOnly = baseImgs.keys.filter { it !in chosen && !(opt.skipMyPreload && it == "my_preload") }.sorted()
        for (n in baseOnly) chosen[n] = baseImgs.getValue(n)
        if (baseOnly.isNotEmpty()) log(tr("Franken: tylko w bazie (zostaja z bazy): ${baseOnly.joinToString()}", "Franken: base only (kept from base): ${baseOnly.joinToString()}"))

        // --- my_product (dawca + konfiguracja bazy) ---
        if (opt.patchMyProduct) {
            val d = donorImgs["my_product"]; val b = baseImgs["my_product"]
            if (d != null && b != null) chosen["my_product"] = patchMyProduct(d, b, File(outSys, "my_product.img"), opt)
            else log(tr("my_product: brak obrazu w bazie lub dawcy - pomijam poprawki", "my_product: image missing in base or donor - skipping fixes"))
        }
        // --- dzwonki ---
        if (opt.ringtones) {
            val prod = chosen["product"]
            val src = File(work, "donor_my_product")
            if (prod != null && src.isDirectory) addRingtones(prod, src, File(outSys, "product.img"))?.let { chosen["product"] = it }
            else log(tr("dzwonki: brak product.img albo wypakowanego my_product - pomijam", "ringtones: no product.img or extracted my_product - skipping"))
        }
        // --- my_manifest (baza + wersja dawcy) ---
        if (opt.patchMyManifest) {
            val b = baseImgs["my_manifest"]; val d = donorImgs["my_manifest"]
            if (b != null && d != null) patchMyManifest(b, d, File(outSys, "my_manifest.img"))?.let { chosen["my_manifest"] = it }
        }
        // --- odm (baza, tozsamosc dawcy) ---
        if (opt.patchOdmIdentity) {
            val b = baseImgs["odm"]; val d = donorImgs["odm"]
            if (b != null && d != null) patchOdmIdentity(b, d, File(outSys, "odm.img"))?.let { chosen["odm"] = it }
        }
        // --- app_v2 ---
        if (opt.unlockAppV2) {
            for (n in listOf("my_stock", "my_region")) {
                val src = chosen[n] ?: continue
                unlockAppV2(src, File(outSys, "$n.img"))?.let { chosen[n] = it }
            }
        }

        val groups = LinkedHashMap<String, List<File>>()
        for (g in listOf("CRITICAL", "EXTRA", "MODEM", "BOOTLOADER")) {
            groups[g] = File(baseDir, g).listFiles { f -> f.name.endsWith(".img", true) && f.length() > 0 }.orEmpty().sortedBy { it.name }
        }
        groups["SYSTEM"] = chosen.values.sortedBy { it.name }
        log("Franken: SYSTEM = " + chosen.entries.joinToString { (n, f) ->
            n + when {
                f.parentFile == outSys -> "*"
                f.parentFile == baseSys -> "(B)"
                else -> ""
            }
        })
        log(tr("  * = poprawiony, (B) = z bazy, reszta z dawcy", "  * = patched, (B) = from base, rest from donor"))
        work.deleteRecursively()
        return Result(groups)
    }

    // =====================================================================================
    // my_product
    // =====================================================================================

    private fun copyImage(src: File, dst: File) {
        step(tr("Kopiowanie ${src.name}", "Copying ${src.name}"))
        dst.parentFile?.mkdirs()
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 4 shl 20) } }
    }

    private fun patchMyProduct(donorImg: File, baseImg: File, out: File, opt: Options): File {
        step(tr("my_product: wypakowanie", "my_product: extracting"))
        val baseRoot = File(work, "base_my_product")
        val donorRoot = File(work, "donor_my_product")
        tools.extractOrThrow(baseImg, baseRoot)
        tools.extractOrThrow(donorImg, donorRoot)
        copyImage(donorImg, out)
        step(tr("my_product: poprawki", "my_product: patching"))
        ErofsPatcher(baseImg, readOnly = true).use { baseP ->
            ErofsPatcher(out).use { p ->
                val t = Tree(p, donorRoot, baseP, baseRoot, "my_product")
                // ReplaceSubtreeFromBase (ostatni argument: usun u dawcy, gdy brak w bazie)
                t.replaceSubtreeFromBase("vendor/etc", false)
                t.replaceSubtreeFromBase("etc/fusionlight_profile", false)
                t.replaceSubtreeFromBase("etc/camera", false)
                t.replaceSubtreeFromBase("etc/vibrator_service_config", true)
                t.replaceSubtreeFromBase("etc/vibrator", true)
                t.replaceSubtreeFromBase("etc/AILightPerceptionConfig", false) // v6: czujnik pod ekranem
                for (f in REPLACE_FILES) t.replaceFileFromBase(f)
                t.mergeNamedElementsFromBase("etc/extension/feature_com.android.battery.xml", "app_feature")
                t.mergeNamedElementsFromBase("etc/extension/config_product_com.android.providers.settings.xml", "settings_item")
                // build.prop
                var forced = 0
                donorRoot.walkTopDown().filter { it.isFile && it.name == "build.prop" }.forEach { f ->
                    val rel = f.relativeTo(donorRoot).invariantSeparatorsPath
                    val bf = File(baseRoot, rel)
                    var lines = f.readLines().toMutableList()
                    var changed = false
                    if (bf.isFile) {
                        val bl = bf.readLines()
                        val n = PropPatches.patchDisplayProps(lines, bl)
                        if (n > 0) { changed = true; log(tr("my_product: $rel - wlasciwosci wyswietlacza/BT/audio z bazy: $n", "my_product: $rel - display/BT/audio props from base: $n")) }
                        if (PropPatches.syncActionButton(lines, bl, log)) changed = true
                    }
                    if (PropPatches.forceProductBuildProps(lines) {}) { changed = true; forced++ }
                    val dups = PropPatches.dedupKeys(lines)
                    if (dups > 0) { changed = true; log(tr("my_product: $rel - usunieto duplikaty: $dups", "my_product: $rel - duplicates removed: $dups")) }
                    if (changed) t.writeText(rel, lines.joinToString("\n") + "\n")
                }
                if (forced > 0) log(tr("my_product: speaker_geq=false / fusionlight=true w $forced plikach build.prop", "my_product: speaker_geq=false / fusionlight=true in $forced build.prop files"))
                Features.transferDeviceFeatures(t, opt.fixVibration, log)
                Features.removeBlacklisted(t)
                val added = Features.ensureAppFeatures(t)
                if (added > 0) log(tr("my_product: app-features - dodano $added", "my_product: app-features - added $added"))
                Features.removeAppFeaturesMissingInBase(t, log)
                if (opt.displayOverlayFromBase) displayOverlay(t)
                val n = p.commit()
                log(tr("my_product: zapisano zmiany (+${n / 1024} KB)", "my_product: changes written (+${n / 1024} KB)"))
            }
        }
        checkImage(out)
        return out
    }

    /** Zamiast przebudowy APK (apktool w FFF): nakladka wyswietlacza dawcy dostaje zawartosc nakladki bazy. */
    private fun displayOverlay(t: Tree) {
        val rx = Regex("""android_framework_res_overlay\.display\.product\..*\.apk""")
        val donor = t.donorRoot.walkTopDown().filter { it.isFile && rx.matches(it.name) }.toList()
        val base = t.baseRoot.walkTopDown().filter { it.isFile && rx.matches(it.name) }.toList()
        if (donor.isEmpty()) { log(tr("nakladka wyswietlacza: brak u dawcy - pomijam", "display overlay: none in donor - skipping")); return }
        if (base.isEmpty()) { log(tr("nakladka wyswietlacza: brak w bazie - pomijam", "display overlay: none in base - skipping")); return }
        for (d in donor) {
            val b = base.firstOrNull { it.name == d.name } ?: base.singleOrNull()
            if (b == null) { log(tr("nakladka wyswietlacza: brak pary dla ${d.name}", "display overlay: no match for ${d.name}")); continue }
            val rel = d.relativeTo(t.donorRoot).invariantSeparatorsPath
            t.writeBytes(rel, b.readBytes())
            log(tr("nakladka wyswietlacza: $rel <- baza ${b.name}", "display overlay: $rel <- base ${b.name}"))
        }
    }

    /** Pliki o danej nazwie w obrazie -> zawartosc (dump.erofs --cat, bez wypakowania). */
    private fun catAll(img: File, name: String): Map<String, ByteArray> {
        val paths = ErofsPatcher(img, readOnly = true).use { findFiles(it, name) }
        return paths.associateWith { tools.cat(img, it) }
    }

    // =====================================================================================
    // dzwonki
    // =====================================================================================

    private fun addRingtones(productImg: File, myProductRoot: File, out: File): File? {
        step(tr("dzwonki", "ringtones"))
        val hr = File(myProductRoot, "res/audio/haptic_ringtone")
        val dirs = listOf(File(hr, "output122HZ")) + hr.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        fun oggs(d: File, prefix: String) = d.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".ogg") }.orEmpty().sortedBy { it.name }
        val src = dirs.firstOrNull { it.isDirectory && oggs(it, "ringtone_").isNotEmpty() }
            ?: dirs.firstOrNull { it.isDirectory && oggs(it, "notification_").isNotEmpty() }
        if (src == null) { log(tr("dzwonki: brak plikow w my_product/res/audio/haptic_ringtone - pomijam", "ringtones: no files in my_product/res/audio/haptic_ringtone - skipping")); return null }
        val plan = LinkedHashMap<String, File>()
        oggs(src, "ringtone_").forEach { plan["media/audio/ringtones/${it.name}"] = it; plan["media/audio/alarms/${it.name}"] = it }
        oggs(src, "notification_").forEach { plan["media/audio/notifications/${it.name}"] = it }
        oggs(src, "alarm_").forEach { plan["media/audio/alarms/${it.name}"] = it }
        val todo = ErofsPatcher(productImg, readOnly = true).use { p -> plan.filterKeys { !p.exists(it) } }
        if (todo.isEmpty()) { log(tr("dzwonki: product ma juz wszystkie pliki - bez zmian", "ringtones: product already has all files - unchanged")); return null }
        copyImage(productImg, out)
        ErofsPatcher(out).use { p ->
            fun ensureDir(path: String) {
                if (path.isEmpty() || p.exists(path)) return
                val parent = path.substringBeforeLast('/', "")
                ensureDir(parent)
                val t = p.templateOf(parent) ?: p.attrsOf(p.rootNid)
                p.mkdir(path, t)
            }
            for ((rel, f) in todo) {
                val dir = rel.substringBeforeLast('/')
                ensureDir(dir)
                val d = p.templateOf(dir) ?: p.attrsOf(p.rootNid)
                p.putFile(rel, f.readBytes(), ErofsPatcher.NewFile(0x81A4, d.uid, d.gid, d.xattrs, ByteArray(0)))
            }
            p.commit()
        }
        log(tr("dzwonki: dodano ${todo.size} plikow z ${src.name} (dzwonki: ${oggs(src, "ringtone_").size}, powiadomienia: ${oggs(src, "notification_").size})", "ringtones: added ${todo.size} files from ${src.name} (ringtones: ${oggs(src, "ringtone_").size}, notifications: ${oggs(src, "notification_").size})"))
        checkImage(out)
        return out
    }

    // =====================================================================================
    // my_manifest
    // =====================================================================================

    private fun patchMyManifest(baseImg: File, donorImg: File, out: File): File? {
        step("my_manifest")
        val donorProps = HashMap<String, String>()
        catAll(donorImg, "build.prop").values.forEach { b ->
            String(b).lines().forEach { l ->
                val s = l.trim(); val i = s.indexOf('=')
                if (s.isNotEmpty() && !s.startsWith("#") && i > 0) {
                    val k = s.substring(0, i).trim()
                    if (isManifestVersionKey(k)) donorProps[k] = s.substring(i + 1).trim()
                }
            }
        }
        log(tr("my_manifest: klucze wersji z dawcy: ", "my_manifest: version keys from donor: ") + (donorProps.keys.sorted().joinToString().ifEmpty { tr("(brak)", "(none)") }))
        if (donorProps.isEmpty()) return null
        val edits = LinkedHashMap<String, String>()
        catAll(baseImg, "build.prop").forEach { (rel, b) ->
            val lines = String(b).lines().toMutableList()
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            var changed = false
            for (i in lines.indices) {
                val s = lines[i].trim(); val eq = s.indexOf('=')
                if (s.isEmpty() || s.startsWith("#") || eq <= 0) continue
                val k = s.substring(0, eq).trim()
                val dv = donorProps[k] ?: continue
                val v = mergeVersion(k, s.substring(eq + 1).trim(), dv)
                val nl = "$k=$v"
                if (lines[i] != nl) { lines[i] = nl; changed = true }
            }
            if (changed) edits[rel] = lines.joinToString("\n") + "\n"
        }
        if (edits.isEmpty()) { log(tr("my_manifest: bez zmian", "my_manifest: unchanged")); return null }
        copyImage(baseImg, out)
        ErofsPatcher(out).use { p ->
            edits.forEach { (rel, txt) -> p.putFile(rel, txt.toByteArray()) }
            p.commit()
        }
        log(tr("my_manifest: wersja z dawcy w ${edits.keys.joinToString()}", "my_manifest: donor version in ${edits.keys.joinToString()}"))
        checkImage(out)
        return out
    }

    private fun isManifestVersionKey(k: String) = k in MANIFEST_WHOLE || k in MANIFEST_PREFIXED || k == "ro.build.display.full_id"

    /** Dla ro.build.display.id*: prefiks modelu z bazy (do pierwszego '_'), reszta z dawcy - jak MergeAfterFirstUnderscore w FFF. */
    private fun mergeVersion(k: String, baseV: String, donorV: String): String {
        if (k in MANIFEST_WHOLE) return donorV
        val bi = baseV.indexOf('_'); val di = donorV.indexOf('_')
        return if (bi > 0 && di > 0) baseV.substring(0, bi) + donorV.substring(di) else donorV
    }

    // =====================================================================================
    // odm: producent/marka jak u dawcy
    // =====================================================================================

    private fun props(files: Map<String, ByteArray>): Map<String, String> {
        val m = HashMap<String, String>()
        files.values.forEach { b ->
            String(b).lines().forEach { l ->
                val s = l.trim(); val i = s.indexOf('=')
                if (s.isNotEmpty() && !s.startsWith("#") && i > 0) m[s.substring(0, i).trim()] = s.substring(i + 1).trim()
            }
        }
        return m
    }

    private fun patchOdmIdentity(baseImg: File, donorImg: File, out: File): File? {
        step(tr("odm: tozsamosc", "odm: identity"))
        val baseFiles = catAll(baseImg, "build.prop")
        val bp = props(baseFiles); val dp = props(catAll(donorImg, "build.prop"))
        fun pick(m: Map<String, String>, what: String) =
            m["ro.product.odm.$what"] ?: m["ro.product.vendor.$what"] ?: m["ro.product.$what"]
        val dMan = pick(dp, "manufacturer"); val dBrand = pick(dp, "brand")
        val bMan = pick(bp, "manufacturer"); val bBrand = pick(bp, "brand")
        if (dMan == null || dBrand == null) { log(tr("odm: brak manufacturer/brand u dawcy - pomijam", "odm: no manufacturer/brand in donor - skipping")); return null }
        if (dMan == bMan && dBrand == bBrand) { log(tr("odm: producent/marka zgodne ($bMan/$bBrand) - bez zmian", "odm: manufacturer/brand match ($bMan/$bBrand) - unchanged")); return null }
        log("odm: manufacturer '$bMan'->'$dMan', brand '$bBrand'->'$dBrand'")
        val edits = LinkedHashMap<String, String>()
        baseFiles.forEach { (rel, b) ->
            val lines = String(b).lines().toMutableList()
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            var changed = false
            for (i in lines.indices) {
                val s = lines[i].trim(); val eq = s.indexOf('=')
                if (s.startsWith("#") || eq <= 0) continue
                val k = s.substring(0, eq).trim()
                val nv = when {
                    k.startsWith("ro.product.") && k.endsWith(".manufacturer") || k == "ro.product.manufacturer" -> dMan
                    k.startsWith("ro.product.") && k.endsWith(".brand") || k == "ro.product.brand" -> dBrand
                    else -> null
                } ?: continue
                if (s.substring(eq + 1).trim() != nv) { lines[i] = "$k=$nv"; changed = true }
            }
            if (changed) edits[rel] = lines.joinToString("\n") + "\n"
        }
        if (edits.isEmpty()) return null
        copyImage(baseImg, out)
        ErofsPatcher(out).use { p -> edits.forEach { (rel, txt) -> p.putFile(rel, txt.toByteArray()) }; p.commit() }
        checkImage(out)
        return out
    }

    // =====================================================================================
    // app_v2: <disable pkg="com.android.contacts" .../> -> <enable .../>
    // =====================================================================================

    private fun findFiles(p: ErofsPatcher, name: String): List<String> {
        val out = ArrayList<String>()
        fun walk(path: String, nid: Long, depth: Int) {
            if (depth > 12) return
            for (e in p.readDir(nid)) {
                if (e.name == "." || e.name == "..") continue
                val child = if (path.isEmpty()) e.name else "$path/${e.name}"
                if (e.type == ErofsPatcher.FT_DIR) walk(child, e.nid, depth + 1)
                else if (e.name == name) out += child
            }
        }
        walk("", p.rootNid, 0)
        return out
    }

    private fun unlockAppV2(src: File, out: File): File? {
        step("app_v2: ${src.name}")
        val files = catAll(src, "app_v2.xml")
        if (files.isEmpty()) return null
        val edits = LinkedHashMap<String, String>()
        for ((rel, bytes) in files) {
            val txt = String(bytes)
            var nt = txt
            for (pkg in APP_V2_ENABLE) nt = Regex("""<disable(\s+pkg="${Regex.escape(pkg)}"[^>]*?)/>""").replace(nt, "<enable$1/>")
            if (nt != txt) edits[rel] = nt
        }
        if (edits.isEmpty()) { log(tr("app_v2: ${src.name} - nic do odblokowania", "app_v2: ${src.name} - nothing to unlock")); return null }
        copyImage(src, out)
        ErofsPatcher(out).use { p -> edits.forEach { (rel, txt) -> p.putFile(rel, txt.toByteArray()) }; p.commit() }
        log(tr("app_v2: ${src.name} - odblokowano pakiety w ${edits.keys.joinToString()}", "app_v2: ${src.name} - packages unlocked in ${edits.keys.joinToString()}"))
        checkImage(out)
        return out
    }

    private fun checkImage(img: File) {
        val rc = tools.check(img)
        if (rc != 0) throw IOException(tr("fsck.erofs ${img.name}: kod $rc - obraz niepoprawny, przerwano", "fsck.erofs ${img.name}: code $rc - invalid image, aborted"))
        log("fsck ${img.name}: OK")
    }

    // =====================================================================================

    /**
     * Drzewo dawcy: zawartosc z wypakowanego katalogu, zapis przez ErofsPatcher,
     * atrybuty (SELinux, uid/gid, tryb) nowych wpisow - z obrazu bazy.
     */
    class Tree(
        val p: ErofsPatcher, val donorRoot: File,
        val baseP: ErofsPatcher, val baseRoot: File, val label: String,
    ) {
        fun donorFile(rel: String) = File(donorRoot, rel)
        fun baseFile(rel: String) = File(baseRoot, rel)

        fun writeText(rel: String, text: String) = writeBytes(rel, text.toByteArray())

        fun writeBytes(rel: String, data: ByteArray) {
            val f = donorFile(rel)
            f.parentFile?.mkdirs()
            f.writeBytes(data)
            ensureParents(rel)
            if (p.exists(rel)) p.putFile(rel, data)
            else p.putFile(rel, data, baseP.templateOf(rel))
        }

        /** Tworzy brakujace katalogi nadrzedne (atrybuty z bazy). */
        private fun ensureParents(rel: String) {
            val parts = rel.split('/').dropLast(1)
            var cur = ""
            for (part in parts) {
                cur = if (cur.isEmpty()) part else "$cur/$part"
                if (!p.exists(cur)) {
                    val t = baseP.templateOf(cur) ?: throw IOException(tr("$label: brak katalogu $cur w bazie i dawcy", "$label: directory $cur missing in base and donor"))
                    p.mkdir(cur, t)
                }
            }
        }

        fun replaceFileFromBase(rel: String) {
            val bf = baseFile(rel)
            val nid = baseP.lookup(rel)
            if (!bf.isFile || nid == null) return
            val data = bf.readBytes()
            donorFile(rel).apply { parentFile?.mkdirs(); writeBytes(data) }
            ensureParents(rel)
            if (p.exists(rel)) p.delete(rel)
            p.putFile(rel, data, baseP.attrsOf(nid))
        }

        fun replaceSubtreeFromBase(rel: String, removeIfMissingInBase: Boolean) {
            val nid = baseP.lookup(rel)
            if (nid == null || !baseP.inode(nid).isDir) {
                if (removeIfMissingInBase && p.exists(rel)) {
                    p.delete(rel); donorFile(rel).deleteRecursively()
                }
                return
            }
            if (baseP.list(rel).isNullOrEmpty()) return // pusty w bazie - dawca zostaje
            ensureParents(rel)
            if (p.exists(rel)) p.delete(rel)
            donorFile(rel).deleteRecursively()
            copyDir(rel, nid)
        }

        private fun copyDir(rel: String, nid: Long) {
            p.mkdir(rel, baseP.attrsOf(nid))
            donorFile(rel).mkdirs()
            for (e in baseP.readDir(nid)) {
                if (e.name == "." || e.name == "..") continue
                val child = "$rel/${e.name}"
                val ino = baseP.inode(e.nid)
                when {
                    ino.isDir -> copyDir(child, e.nid)
                    ino.isLink -> p.putFile(child, baseP.readFlat(ino), baseP.attrsOf(e.nid))
                    ino.isReg -> {
                        val data = baseFile(child).readBytes()
                        donorFile(child).writeBytes(data)
                        p.putFile(child, data, baseP.attrsOf(e.nid))
                    }
                }
            }
        }

        fun mergeNamedElementsFromBase(rel: String, tag: String) {
            val bf = baseFile(rel)
            if (!bf.isFile) return
            val df = donorFile(rel)
            if (!df.isFile) { replaceFileFromBase(rel); return }
            val rx = Regex("<" + Regex.escape(tag) + """\b[^>]*\bname\s*=\s*"([^"]+)"[^>]*/?>""", RegexOption.IGNORE_CASE)
            val have = rx.findAll(df.readText()).map { it.groupValues[1] }.toHashSet()
            val add = ArrayList<String>()
            for (m in rx.findAll(bf.readText())) if (have.add(m.groupValues[1])) add += m.value.trim()
            if (add.isEmpty()) return
            val lines = df.readLines().toMutableList()
            var idx = lines.indexOfLast { it.trimStart().startsWith("</") }
            if (idx < 0) idx = lines.size
            lines.addAll(idx, add.map { "    $it" })
            writeText(rel, lines.joinToString("\n") + "\n")
        }
    }

    companion object {
        val HW_FROM_BASE = listOf("odm", "odm_dlkm", "vendor", "vendor_dlkm", "system_dlkm")

        val REPLACE_FILES = listOf(
            "etc/hw/usb_params.json", "etc/oplus_vrr_config.json", "etc/refresh_rate_config.xml",
            "etc/sys_perf_config.json", "etc/sys_resolution_switch_config.xml", "etc/dolby_vision.cfg",
            "etc/sar_config.xml", "etc/wifi_product_config.xml", "etc/wifiedccaconfig.xml", "etc/wifisarconfig.xml",
            "etc/permissions/oplus.features.connectivity.xml", "etc/extension/com.oplus.oplus-feature.xml",
            "etc/permissions/oplus.product.display_features.xml", "etc/permissions/oplus.product.feature_multimedia_unique.xml",
            "etc/extension/camera_enhance_curve_config.xml",
            "etc/audio_policy_configuration.xml", "etc/audio_policy_volumes.xml", "etc/default_volume_tables.xml",
            "etc/Multimedia_Feedback_List.xml",
        )

        val MANIFEST_WHOLE = setOf("ro.build.version.oplusrom", "ro.build.version.oplusrom.confidential", "ro.build.version.oplusrom.display")
        val MANIFEST_PREFIXED = listOf("ro.build.display.id", "ro.build.display.id.show")

        val APP_V2_ENABLE = listOf(
            "com.android.contacts", "com.android.incallui", "com.android.mms", "com.oplus.blacklistapp",
            "com.oplus.aiassistant", "com.oplus.aimemory", "com.oplus.aipaint", "com.oplus.aiwidgets",
        )
    }
}
