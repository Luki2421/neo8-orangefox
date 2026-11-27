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
        /** Biblioteki my_product/lib64 (aparat, efekty) do product/lib64 - w dawcy widoczne przez nakladke z jego fstab,
         *  ktorej vendor bazy nie ma (Aparat: libCombineLutJni.so not found). */
        var productLibs: Boolean = true,
        /** APK tej aplikacji jako uprzywilejowana aplikacja systemowa (product/priv-app) z REBOOT/RECOVERY -
         *  restart do recovery/bootloader/fastbootd bez roota, jak w custom ROM. */
        var appApk: File? = null,
        /** Nagrywanie rozmow (jak modul "Mms + call recording lite" / or965): flagi regionu w my_region, my_stock
         *  i my_product (etc/extension), ktore ukrywaja nagrywanie; Wiadomosci (com.android.mms) odblokowuje app_v2. */
        var callRecording: Boolean = true,
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

        checkAndroidVersions(chosen["system"], baseImgs["system"], chosen["vendor"])

        // --- dawca AOSP (LineageOS/DerpFest...): bez partycji my_* ---
        val aosp = "my_product" !in donorImgs && "my_stock" !in donorImgs
        if (aosp) {
            log(tr("Franken: dawca AOSP (brak my_*) - system/system_ext/product z dawcy, vendor/odm/boot/firmware z bazy; " +
                "my_* zostaja z bazy (vendor ColorOS je montuje, system AOSP ich nie czyta); poprawki ColorOS pominiete",
                "Franken: AOSP donor (no my_*) - system/system_ext/product from donor, vendor/odm/boot/firmware from base; " +
                "my_* kept from base (ColorOS vendor mounts them, AOSP system ignores them); ColorOS fixes skipped"))
            checkFstab(chosen)
            checkSepolicy(chosen, outSys)
        }

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
        // --- biblioteki my_product -> product/lib64 ---
        if (opt.productLibs) {
            val prod = chosen["product"]
            val src = File(work, "donor_my_product")
            if (prod != null && src.isDirectory) addProductLibs(prod, src, File(outSys, "product.img"))?.let { chosen["product"] = it }
            else log(tr("biblioteki: brak product.img albo wypakowanego my_product - pomijam", "libraries: no product.img or extracted my_product - skipping"))
        }
        // --- Franken Flasher jako aplikacja systemowa ---
        opt.appApk?.takeIf { it.isFile }?.let { apk ->
            val prod = chosen["product"]
            if (prod != null && fsType(prod) != "erofs") log(tr("Franken Flasher nie zostanie wbudowany: product dawcy to ${fsType(prod) ?: "nieznany system plikow"}, nie EROFS",
                "Franken Flasher not embedded: donor product is ${fsType(prod) ?: "an unknown filesystem"}, not EROFS"))
            else if (prod != null) embedApp(prod, apk, File(outSys, "product.img"))?.let { chosen["product"] = it }
        }
        // --- my_manifest (baza + wersja dawcy) ---
        if (opt.patchMyManifest && !aosp) {
            val b = baseImgs["my_manifest"]; val d = donorImgs["my_manifest"]
            if (b != null && d != null) patchMyManifest(b, d, File(outSys, "my_manifest.img"))?.let { chosen["my_manifest"] = it }
        }
        // --- odm (baza, tozsamosc dawcy) ---
        if (opt.patchOdmIdentity && !aosp) {
            val b = baseImgs["odm"]; val d = donorImgs["odm"]
            if (b != null && d != null) patchOdmIdentity(b, d, File(outSys, "odm.img"))?.let { chosen["odm"] = it }
        }
        // --- app_v2 ---
        if (opt.unlockAppV2 && !aosp) {
            for (n in listOf("my_stock", "my_region")) {
                val src = chosen[n] ?: continue
                unlockAppV2(src, File(outSys, "$n.img"))?.let { chosen[n] = it }
            }
        }
        // --- nagrywanie rozmow ---
        if (opt.callRecording && !aosp) {
            for (n in listOf("my_region", "my_stock", "my_product")) {
                val src = chosen[n] ?: continue
                enableCallRecording(n, src, File(outSys, "$n.img"))?.let { chosen[n] = it }
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

    /** Wlasciwosci z pierwszego istniejacego build.prop w obrazie (bez wypakowania). */
    private fun readProps(img: File?, paths: List<String>): Map<String, String> {
        if (img == null || !img.isFile) return emptyMap()
        return try {
            for (path in paths) imgCat(img, path)?.let { return props(mapOf(path to it)) }
            emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    /** "erofs", "ext4" albo null. ROM-y AOSP (LineageOS, DerpFest) czesto maja ext4. */
    private fun fsType(img: File?): String? = when {
        img == null || !img.isFile -> null
        Ext4Reader.isExt4(img) -> "ext4"
        try { ErofsPatcher(img, readOnly = true).use { true } } catch (_: Exception) { false } -> "erofs"
        else -> null
    }

    /** Plik z obrazu EROFS (dump.erofs) albo ext4 (Ext4Reader); null gdy brak. */
    private fun imgCat(img: File, path: String): ByteArray? = when (fsType(img)) {
        "ext4" -> Ext4Reader(img).use { it.cat(path) }
        "erofs" -> {
            val p = path.trimStart('/')
            if (ErofsPatcher(img, readOnly = true).use { it.lookup(p) } == null) null else tools.cat(img, p)
        }
        else -> null
    }

    /**
     * Czy fstab vendora bazy zamontuje partycje dawcy w ich systemie plikow (dawca AOSP: czesto ext4,
     * ColorOS: tylko EROFS). Gdy fstab nie ma wpisu z typem obrazu dawcy - system sie nie uruchomi, wiec przerywa.
     */
    private fun checkFstab(chosen: Map<String, File>) {
        val vendor = chosen["vendor"] ?: return
        val names = try { ErofsPatcher(vendor, readOnly = true).use { p -> p.list("etc")?.map { it.name }.orEmpty() } } catch (_: Exception) { emptyList() }
        val fstabs = names.filter { it.startsWith("fstab.") }
        if (fstabs.isEmpty()) { log(tr("fstab: brak vendor/etc/fstab.* w bazie - nie sprawdzam", "fstab: no vendor/etc/fstab.* in base - not checked")); return }
        val types = HashMap<String, MutableSet<String>>()
        for (f in fstabs) {
            val txt = imgCat(vendor, "etc/$f")?.let { String(it) } ?: continue
            for (l in txt.lines()) {
                val c = l.trim().split(Regex("\\s+"))
                if (c.size < 3 || c[0].startsWith("#")) continue
                types.getOrPut(c[1]) { HashSet() } += c[2]
            }
        }
        val bad = ArrayList<String>()
        for ((part, mnt) in listOf("system" to "/system", "system_ext" to "/system_ext", "product" to "/product")) {
            val t = fsType(chosen[part]) ?: continue
            val ok = types[mnt]
            log(tr("fstab: $part ($t) -> $mnt: ${ok?.joinToString() ?: "brak wpisu"}", "fstab: $part ($t) -> $mnt: ${ok?.joinToString() ?: "no entry"}"))
            if (ok != null && t !in ok) bad += "$part ($t, fstab: ${ok.joinToString()})"
        }
        if (bad.isNotEmpty()) throw IOException(tr("fstab bazy nie zamontuje: ${bad.joinToString()} - system by sie nie uruchomil. Przerywam.",
            "base fstab will not mount: ${bad.joinToString()} - the system would not boot. Stopping."))
    }

    /**
     * Porownuje Androida systemu dawcy z systemem BAZY. Vendor bazy moze byc starszy od jej systemu
     * (zamrozony vendor / GRF, np. ColorOS 17 z vendorem Android 16) - to normalne, bo baza sama tak dziala;
     * ryzyko jest wtedy, gdy system dawcy rozni sie od systemu, z ktorym ten vendor dziala w bazie.
     */
    private fun checkAndroidVersions(donorSystem: File?, baseSystem: File?, vendor: File?) {
        fun sys(img: File?): Pair<String?, Int?> {
            val p = readProps(img, listOf("system/build.prop", "build.prop"))
            return (p["ro.system.build.version.release"] ?: p["ro.build.version.release"]) to (p["ro.system.build.version.sdk"] ?: p["ro.build.version.sdk"])?.toIntOrNull()
        }
        val (dRel, dSdk) = sys(donorSystem)
        val (bRel, bSdk) = sys(baseSystem)
        val vp = readProps(vendor, listOf("build.prop", "etc/build.prop"))
        val vRel = vp["ro.vendor.build.version.release"]
        val vSdk = vp["ro.vendor.build.version.sdk"]?.toIntOrNull()
        if (dSdk == null || (bSdk == null && vSdk == null)) { log(tr("Franken: nie udalo sie odczytac wersji Androida (system/vendor) - sprawdz recznie", "Franken: could not read Android versions (system/vendor) - check manually")); return }
        log(tr("Franken: system dawcy Android $dRel (SDK $dSdk), system bazy Android ${bRel ?: "?"} (SDK ${bSdk ?: "?"}), vendor bazy Android ${vRel ?: "?"} (SDK ${vSdk ?: "?"})",
            "Franken: donor system Android $dRel (SDK $dSdk), base system Android ${bRel ?: "?"} (SDK ${bSdk ?: "?"}), base vendor Android ${vRel ?: "?"} (SDK ${vSdk ?: "?"})"))
        val ref = bSdk ?: vSdk!!
        val refRel = if (bSdk != null) bRel else vRel
        if (dSdk != ref) log(tr("Franken: UWAGA - rozne wersje Androida dawcy ($dRel) i bazy ($refRel). Duze ryzyko bootloopa - uzyj bazy i dawcy z tym samym Androidem.",
            "Franken: WARNING - donor ($dRel) and base ($refRel) Android versions differ. High bootloop risk - use base and donor with the same Android."))
        else if (vSdk != null && vSdk < dSdk) log(tr("Franken: vendor bazy starszy od systemu (zamrozony vendor/GRF) - tak samo jak w samej bazie, to normalne",
            "Franken: base vendor older than system (frozen vendor/GRF) - same as in the base itself, this is normal"))
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

    /**
     * SELinux dawcy AOSP + vendor bazy: kompilacja polityki dokladnie tak jak init przy starcie
     * (precompiled_sepolicy vendora nie pasuje do systemu dawcy, wiec init kompiluje sam):
     *   secilc plat_sepolicy.cil -m -M true -G -N -c 30 mapping/<ver>.cil [<ver>.compat.cil] [genfs]
     *          [system_ext_sepolicy.cil + mapping + compat] [product_sepolicy.cil + mapping]
     *          plat_pub_versioned.cil vendor_sepolicy.cil [odm_sepolicy.cil]
     * secilc z aplikacji (libsepol, budowany w CI; /system/bin/secilc jest dla aplikacji zablokowany - error=13).
     *  - "-m": te same typy w dawcy i w vendorze sa dozwolone (to nie blad)
     *  - atrybuty wersjonowane (np. sh_exec_202504), ktore vendor ColorOS bierze z systemu ColorOS, a ktorych dawca
     *    nie ma -> "Failed to resolve" -> bootloop. Poprawka: puste deklaracje dopisane do plat_pub_versioned.cil
     *    vendora bazy (reguly vendora dla typow, ktorych w AOSP nie ma, po prostu nic nie robia)
     *  - inny blad rozwiazywania nazw -> przerwanie (system by nie wstal)
     * Gdy secilc nie da sie uruchomic - sprawdzenie przyblizone (tylko wersjonowane nazwy).
     */
    private fun checkSepolicy(chosen: MutableMap<String, File>, outSys: File) {
        step(tr("SELinux: kompilacja polityki (dawca + vendor bazy)", "SELinux: compiling policy (donor + base vendor)"))
        fun cat(n: String, vararg paths: String): ByteArray? {
            val img = chosen[n] ?: return null
            for (p in paths) try { imgCat(img, p)?.let { return it } } catch (_: Exception) {}
            return null
        }
        fun sys(p: String) = cat("system", "system/$p", p)
        fun part(n: String, p: String) = cat(n, p) ?: sys("$n/$p")
        val ver = cat("vendor", "etc/selinux/plat_sepolicy_vers.txt")?.let { String(it).trim() }
        if (ver.isNullOrEmpty()) { log(tr("SELinux: brak vendor/etc/selinux/plat_sepolicy_vers.txt - pomijam sprawdzenie", "SELinux: no plat_sepolicy_vers.txt - check skipped")); return }
        val plat = sys("etc/selinux/plat_sepolicy.cil")
        val pub = cat("vendor", "etc/selinux/plat_pub_versioned.cil")
        val vend = cat("vendor", "etc/selinux/vendor_sepolicy.cil")
        if (plat == null || pub == null || vend == null) {
            log(tr("SELinux: nie odczytano plikow polityki (plat_sepolicy: ${plat != null}, plat_pub_versioned: ${pub != null}, vendor_sepolicy: ${vend != null}) - pomijam sprawdzenie",
                "SELinux: policy files not read (plat_sepolicy: ${plat != null}, plat_pub_versioned: ${pub != null}, vendor_sepolicy: ${vend != null}) - check skipped"))
            return
        }
        val map = sys("etc/selinux/mapping/$ver.cil") ?: run {
            val have = chosen["system"]?.let { imgList(it, "system/etc/selinux/mapping") }?.filter { !it.contains("compat") }?.map { it.removeSuffix(".cil") }?.sorted()
            throw IOException(tr("SELinux: system dawcy nie ma mapping/$ver.cil - nie obsluguje vendora $ver bazy (ma: ${have?.joinToString() ?: "?"}). Init nie zaladuje polityki - bootloop. Przerywam.",
                "SELinux: donor system has no mapping/$ver.cil - it does not support base vendor $ver (has: ${have?.joinToString() ?: "?"}). Init cannot load the policy - bootloop. Stopping."))
        }
        val gv = cat("vendor", "etc/selinux/genfs_labels_version.txt")?.let { String(it).trim() }
        // kolejnosc i zestaw jak w init (selinux.cpp, OpenSplitPolicy)
        val inputs = ArrayList<Pair<String, ByteArray>>()
        fun add(name: String, data: ByteArray?) { if (data != null) inputs += name to data }
        add("plat_sepolicy.cil", plat)
        add("plat_mapping_$ver.cil", map)
        add("plat_$ver.compat.cil", sys("etc/selinux/mapping/$ver.compat.cil"))
        if (!gv.isNullOrEmpty()) add("plat_sepolicy_genfs_$gv.cil", sys("etc/selinux/plat_sepolicy_genfs_$gv.cil"))
        add("system_ext_sepolicy.cil", part("system_ext", "etc/selinux/system_ext_sepolicy.cil"))
        add("system_ext_mapping_$ver.cil", part("system_ext", "etc/selinux/mapping/$ver.cil"))
        add("system_ext_$ver.compat.cil", part("system_ext", "etc/selinux/mapping/$ver.compat.cil"))
        add("product_sepolicy.cil", part("product", "etc/selinux/product_sepolicy.cil"))
        add("product_mapping_$ver.cil", part("product", "etc/selinux/mapping/$ver.cil"))
        add(PUB_CIL, pub)
        add("vendor_sepolicy.cil", vend)
        add("odm_sepolicy.cil", cat("odm", "etc/selinux/odm_sepolicy.cil"))

        val suffix = "_" + ver.replace('.', '_')
        // bez komentarzy CIL (';' do konca linii)
        val texts = inputs.associate { (n, d) -> n to String(d).lineSequence().joinToString("\n") { it.substringBefore(';') } }
        val declared = texts.values.flatMapTo(HashSet()) { t -> CIL_DECL.findAll(t).map { it.groupValues[1] } }
        val vendorSide = listOfNotNull(texts["vendor_sepolicy.cil"], texts["odm_sepolicy.cil"])
        val dup = run {
            val donorSide = listOfNotNull(texts["system_ext_sepolicy.cil"], texts["product_sepolicy.cil"]).flatMapTo(HashSet()) { t -> CIL_TYPE.findAll(t).map { it.groupValues[1] } }
            vendorSide.flatMapTo(HashSet()) { t -> CIL_TYPE.findAll(t).map { it.groupValues[1] } }.intersect(donorSide)
        }
        // wersjonowane nazwy uzyte przez vendor/odm, ktorych nikt nie deklaruje (z systemu ColorOS)
        val extra = LinkedHashSet<String>()
        vendorSide.forEach { t -> Regex("""\b([A-Za-z0-9_]+$suffix)\b""").findAll(t).forEach { m -> if (m.groupValues[1] !in declared) extra += m.groupValues[1] } }
        fun short(l: Collection<String>) = l.take(12).joinToString() + if (l.size > 12) " ... (+${l.size - 12})" else ""
        fun pubWithExtra(): ByteArray = if (extra.isEmpty()) pub else (String(pub).trimEnd() + "\n; Franken: atrybuty z systemu ColorOS, ktorych nie ma dawca AOSP (puste)\n" +
            extra.joinToString("") { "(typeattribute $it)\n" }).toByteArray()

        val dir = File(work, "selinux").apply { deleteRecursively(); mkdirs() }
        val files = inputs.map { (n, d) -> File(dir, n).apply { writeBytes(d) } }
        var verdict: String? = null    // null = OK
        var rounds = 0
        val compiled: Boolean = try {
            while (true) {
                File(dir, PUB_CIL).writeBytes(pubWithExtra())
                val err = secilc(files, dir)
                if (err == null) { verdict = null; break }
                rounds++
                val m = Regex("""Failed to resolve \S+ statement at (\S+?):(\d+)""").find(err)
                val src = m?.groupValues?.get(1)?.let { File(it).let { f -> if (f.isAbsolute) f else File(dir, it) } }?.takeIf { it.isFile }
                val stmt = src?.useLines { s -> s.drop(m!!.groupValues[2].toInt() - 1).firstOrNull() }?.trim()
                val cand = stmt?.let { s -> Regex("""[A-Za-z0-9_]+""").findAll(s).map { it.value }.filter { it.endsWith(suffix) }.toSet() + typeTokens(s) }.orEmpty()
                val missing = cand.filter { it !in declared && it !in extra }.toSet()
                if (missing.isEmpty() || rounds > 40) {
                    verdict = err.lines().filter { it.isNotBlank() }.take(4).joinToString(" | ") + (if (src != null && stmt != null) " | ${src.name}: ${stmt.take(200)}" else "")
                    break
                }
                extra += missing
            }
            true
        } catch (e: Exception) {
            log(tr("SELinux: secilc niedostepny (${e.message}) - sprawdzenie przyblizone", "SELinux: secilc unavailable (${e.message}) - approximate check"))
            false
        } finally { dir.deleteRecursively() }

        if (dup.isNotEmpty()) log(tr("SELinux: te same typy w dawcy i vendorze (${dup.size}): ${short(dup.sorted())} - dozwolone (init kompiluje z -m)",
            "SELinux: same types in donor and vendor (${dup.size}): ${short(dup.sorted())} - allowed (init compiles with -m)"))
        verdict?.let { v ->
            val fatal = v.contains("Failed to resolve") || v.contains("Re-declaration")
            if (fatal) throw IOException(tr("SELinux: polityka sie nie skompiluje przy starcie: $v - system by nie wstal. Przerywam.",
                "SELinux: the policy will not compile at boot: $v - the system would not boot. Stopping."))
            log(tr("SELinux: UWAGA - secilc ($secilcUsed) zglosil blad (moze inna wersja libsepol niz w dawcy): $v - ryzyko bootloopa",
                "SELinux: WARNING - secilc ($secilcUsed) reported an error (maybe a different libsepol than the donor): $v - bootloop risk"))
        }
        if (extra.isNotEmpty()) {
            val v = chosen.getValue("vendor")
            if (fsType(v) != "erofs") throw IOException(tr("SELinux: vendor bazy wymaga ${extra.size} atrybutow (${short(extra)}), ale nie jest EROFS - nie moge go poprawic. Przerywam.",
                "SELinux: base vendor needs ${extra.size} attributes (${short(extra)}) but is not EROFS - cannot patch it. Stopping."))
            val out = File(outSys, "vendor.img")
            copyImage(v, out)
            ErofsPatcher(out).use { p -> p.putFile("etc/selinux/$PUB_CIL", pubWithExtra()); p.commit() }
            checkImage(out)
            chosen["vendor"] = out
            log(tr("SELinux: vendor - dopisano ${extra.size} pustych atrybutow do etc/selinux/$PUB_CIL: ${short(extra)} (nazwy z systemu ColorOS, ktorych nie ma dawca - reguly vendora dla nich nic nie robia)",
                "SELinux: vendor - added ${extra.size} empty attributes to etc/selinux/$PUB_CIL: ${short(extra)} (ColorOS system names the donor lacks - vendor rules for them do nothing)"))
        }
        if (compiled && verdict == null) log(tr("SELinux: OK - polityka kompiluje sie jak przy starcie ($secilcUsed, ${inputs.size} plikow${if (extra.isEmpty()) "" else ", z poprawka vendora"})",
            "SELinux: OK - the policy compiles as at boot ($secilcUsed, ${inputs.size} files${if (extra.isEmpty()) "" else ", with the vendor fix"})"))
        if (!compiled) log(tr("SELinux: przyblizone - bez kompilacji nie da sie wykluczyc bootloopa", "SELinux: approximate - without compiling a bootloop cannot be ruled out"))
    }

    /**
     * Nazwy w miejscu typu/atrybutu w regule CIL: zrodlo i cel allow/typetransition, atrybut i wyrazenie
     * typeattributeset. Wynik typetransition (musi byc typem) - nie, tego nie da sie zastapic pustym atrybutem.
     */
    private fun typeTokens(stmt: String): Set<String> {
        val tok = Regex("""\(|\)|[^\s()]+""").findAll(stmt).map { it.value }.toList()
        var i = 0
        fun parse(): Any {
            if (i >= tok.size) throw IOException("cil")
            if (tok[i] != "(") return tok[i++]
            i++
            val l = ArrayList<Any>()
            while (i < tok.size && tok[i] != ")") l += parse()
            i++
            return l
        }
        val e = (try { parse() } catch (_: Exception) { null }) as? List<*> ?: return emptySet()
        fun flat(x: Any?): List<String> = when (x) { is String -> listOf(x); is List<*> -> x.flatMap { flat(it) }; else -> emptyList() }
        val names = when (e.firstOrNull()) {
            "allow", "auditallow", "dontaudit", "neverallow", "allowx", "auditallowx", "dontauditx", "neverallowx",
            "typetransition", "typechange", "typemember" -> flat(e.getOrNull(1)) + flat(e.getOrNull(2))
            "typeattributeset" -> flat(e.getOrNull(1)) + flat(e.getOrNull(2))
            else -> emptyList()
        }
        return names.filter { it !in CIL_OPS && CIL_NAME.matches(it) }.toSet()
    }

    private var secilcUsed = "secilc"

    /** Kompilacja jak w init. null = OK, tekst = blad. Wyjatek, gdy secilc nie da sie uruchomic. */
    private fun secilc(files: List<File>, dir: File): String? {
        // wlasny (jniLibs, wykonywalny dla aplikacji), potem systemowy (zwykle zablokowany: error=13)
        val bin = tools.secilc?.takeIf { it.canExecute() } ?: File(SECILC).takeIf { it.isFile }
            ?: throw IOException(tr("brak secilc (libsecilc.so i $SECILC)", "no secilc (libsecilc.so and $SECILC)"))
        secilcUsed = if (bin == tools.secilc) tr("secilc aplikacji", "app secilc") else bin.path
        val out = File(dir, "policy.out")
        val cmd = listOf(bin.path, files[0].absolutePath, "-m", "-M", "true", "-G", "-N", "-c", "30", files[1].absolutePath, "-o", out.absolutePath, "-f", "/dev/null") +
            files.drop(2).map { it.absolutePath }
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("secilc: timeout") }
        out.delete()
        return if (p.exitValue() == 0) null else text.trim().ifEmpty { "secilc: kod ${p.exitValue()}" }
    }

    /** Nazwy w katalogu obrazu EROFS albo ext4. */
    private fun imgList(img: File, path: String): List<String>? = try {
        when (fsType(img)) {
            "ext4" -> Ext4Reader(img).use { it.list(path) }
            "erofs" -> ErofsPatcher(img, readOnly = true).use { p -> p.list(path.trimStart('/'))?.map { it.name } }
            else -> null
        }?.filter { it != "." && it != ".." }
    } catch (_: Exception) { null }

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
        // zrodla po kolei: my_product dawcy, potem my_product bazy (np. OPPO nie ma haptic_ringtone)
        val roots = listOf(myProductRoot, File(work, "base_my_product")).filter { it.isDirectory }
        val plan = LinkedHashMap<String, File>()
        var srcName = ""
        for (root in roots) {
            ringtonePlan(root).takeIf { it.isNotEmpty() }?.let { plan.putAll(it); srcName = if (root == myProductRoot) tr("dawcy", "donor") else tr("bazy", "base"); }
            if (plan.isNotEmpty()) break
        }
        if (plan.isEmpty()) { log(tr("dzwonki: brak plikow dzwonkow w my_product dawcy i bazy - pomijam", "ringtones: no ringtone files in donor or base my_product - skipping")); return null }
        val nRing = plan.keys.count { it.startsWith("media/audio/ringtones/") }
        val nNotif = plan.keys.count { it.startsWith("media/audio/notifications/") }
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
        log(tr("dzwonki: dodano ${todo.size} plikow z my_product $srcName (dzwonki: $nRing, powiadomienia: $nNotif)", "ringtones: added ${todo.size} files from $srcName my_product (ringtones: $nRing, notifications: $nNotif)"))
        checkImage(out)
        return out
    }

    /** Pliki dzwonkow w wypakowanym my_product: haptic_ringtone (ringtone_/notification_/alarm_*.ogg)
     *  albo katalogi ringtones/notifications/alarms z plikami audio. */
    private fun ringtonePlan(root: File): Map<String, File> {
        val audio = setOf("ogg", "mp3", "flac", "wav", "m4a")
        fun files(d: File) = d.listFiles { f -> f.isFile && f.extension.lowercase() in audio }.orEmpty().sortedBy { it.name }
        val plan = LinkedHashMap<String, File>()
        val hr = File(root, "res/audio/haptic_ringtone")
        val hdirs = listOf(File(hr, "output122HZ")) + hr.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        hdirs.firstOrNull { d -> d.isDirectory && files(d).any { it.name.startsWith("ringtone_") || it.name.startsWith("notification_") } }?.let { d ->
            files(d).forEach { f ->
                when {
                    f.name.startsWith("ringtone_") -> { plan["media/audio/ringtones/${f.name}"] = f; plan["media/audio/alarms/${f.name}"] = f }
                    f.name.startsWith("notification_") -> plan["media/audio/notifications/${f.name}"] = f
                    f.name.startsWith("alarm_") -> plan["media/audio/alarms/${f.name}"] = f
                }
            }
            return plan
        }
        // katalogi ringtones / notifications / alarms (np. media/audio/... w my_product)
        root.walkTopDown().maxDepth(5).filter { it.isDirectory }.forEach { d ->
            val cat = when (d.name.lowercase()) { "ringtones", "ringtone" -> "ringtones"; "notifications", "notification" -> "notifications"; "alarms", "alarm" -> "alarms"; else -> null }
            if (cat != null) files(d).forEach { f -> plan.putIfAbsent("media/audio/$cat/${f.name}", f) }
        }
        return plan
    }

    /** my_product/lib64 i lib dawcy -> product/lib64 i lib (jak nakladka lowerdir=/my_product/lib64:/product/lib64). */
    private fun addProductLibs(productImg: File, myProductRoot: File, out: File): File? {
        step(tr("biblioteki my_product", "my_product libraries"))
        val plan = LinkedHashMap<String, File>()
        for (d in listOf("lib64", "lib")) {
            File(myProductRoot, d).listFiles { f -> f.name.endsWith(".so") && !java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isFile }.orEmpty()
                .sortedBy { it.name }.forEach { plan["$d/${it.name}"] = it }
        }
        if (plan.isEmpty()) { log(tr("biblioteki: my_product nie ma lib64 - pomijam", "libraries: my_product has no lib64 - skipping")); return null }
        if (productImg.canonicalPath != out.canonicalPath) copyImage(productImg, out)
        var added = 0; var replaced = 0; var bytes = 0L
        ErofsPatcher(out).use { p ->
            for (d in plan.keys.map { it.substringBefore('/') }.distinct()) {
                if (!p.exists(d)) p.mkdir(d, p.templateOf("lib64") ?: p.templateOf("etc") ?: p.attrsOf(p.rootNid))
            }
            val fileTpl = p.list("lib64")?.firstOrNull { it.name.endsWith(".so") }?.let { p.templateOf("lib64/${it.name}") }
            for ((rel, f) in plan) {
                if (p.exists(rel)) replaced++ else added++
                p.putFile(rel, f.readBytes(), fileTpl)
                bytes += f.length()
            }
            p.commit()
        }
        log(tr("biblioteki: my_product/lib64 -> product/lib64: dodano $added, podmieniono $replaced (${bytes / (1 shl 20)} MB) - Aparat i efekty dawcy",
            "libraries: my_product/lib64 -> product/lib64: added $added, replaced $replaced (${bytes / (1 shl 20)} MB) - donor Camera and effects"))
        checkImage(out)
        return out
    }

    /** product/priv-app/FrankenFlasher (APK + lib/arm64) i privapp-permissions z REBOOT i RECOVERY. */
    private fun embedApp(productImg: File, apk: File, out: File): File? {
        step(tr("Franken Flasher -> aplikacja systemowa", "Franken Flasher -> system app"))
        val pkg = "pl.luki.frankenflasher"
        val libs = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipFile(apk).use { z ->
            z.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .forEach { e -> libs[e.name.substringAfterLast('/')] = z.getInputStream(e).use { it.readBytes() } }
        }
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="$pkg">
        <permission name="android.permission.REBOOT"/>
        <permission name="android.permission.RECOVERY"/>
    </privapp-permissions>
</permissions>
"""
        if (productImg.canonicalPath != out.canonicalPath) copyImage(productImg, out)
        ErofsPatcher(out).use { p ->
            // wzory atrybutow (etykiety SELinux) z istniejacych wpisow product
            fun firstFile(dir: String, depth: Int = 2): String? {
                val l = p.list(dir) ?: return null
                l.firstOrNull { it.type == 1 }?.let { return "$dir/${it.name}" }
                if (depth > 0) for (d in l.filter { it.type == 2 }) firstFile("$dir/${d.name}", depth - 1)?.let { return it }
                return null
            }
            val dirTpl = p.templateOf("priv-app") ?: p.templateOf("app") ?: p.templateOf("etc") ?: p.attrsOf(p.rootNid)
            val apkTpl = (firstFile("priv-app") ?: firstFile("app") ?: firstFile("etc"))?.let { p.templateOf(it) }
                ?: ErofsPatcher.NewFile(0x81A4, dirTpl.uid, dirTpl.gid, dirTpl.xattrs, ByteArray(0))
            fun ensureDir(path: String) {
                if (path.isEmpty() || p.exists(path)) return
                ensureDir(path.substringBeforeLast('/', ""))
                p.mkdir(path, dirTpl)
            }
            val base = "priv-app/FrankenFlasher"
            ensureDir("$base/lib/arm64")
            p.putFile("$base/FrankenFlasher.apk", apk.readBytes(), apkTpl)
            // Narzedzia erofs (libmkfs_erofs.so itd.) sa uruchamiane jako programy - musza miec prawo wykonania (0755),
            // inaczej wbudowana aplikacja dostaje "error=13, Permission denied".
            val libTpl = ErofsPatcher.NewFile(0x81ED, apkTpl.uid, apkTpl.gid, apkTpl.xattrs, ByteArray(0))
            for ((n, b) in libs) p.putFile("$base/lib/arm64/$n", b, libTpl)
            ensureDir("etc/permissions")
            val xmlTpl = firstFile("etc/permissions", 0)?.let { p.templateOf(it) } ?: apkTpl
            p.putFile("etc/permissions/privapp-permissions-$pkg.xml", xml.toByteArray(), xmlTpl)
            p.commit()
        }
        log(tr("Franken Flasher wbudowany w product/priv-app (${apk.length() / 1024} KB, bibliotek: ${libs.size}) - restart do recovery/bootloader/fastbootd bez roota",
            "Franken Flasher embedded in product/priv-app (${apk.length() / 1024} KB, libraries: ${libs.size}) - reboot to recovery/bootloader/fastbootd without root"))
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

    /**
     * Nagrywanie rozmow - te same zmiany co customize.sh modulu "Enable Mms + call recording (lite)":
     *  - my_region etc/extension/com.oplus.app-features.xml: + oplus_dialer_enable (phone, telecom), - no_display_record
     *  - my_product etc/extension/<kraj>/appfeature.country.dynamic_features.xml: - no_display_record,
     *    - incallui.not_support_record, - incallui.support_record_prompt
     *  - my_stock etc/extension/com.oplus.app-features.xml: - bloki hide_call_record_mcc i support_call_record_prompt_mcc
     */
    private fun enableCallRecording(name: String, src: File, out: File): File? {
        step(tr("nagrywanie rozmow: $name", "call recording: $name"))
        val target = when (name) {
            "my_product" -> "appfeature.country.dynamic_features.xml"
            else -> "com.oplus.app-features.xml"
        }
        val files = catAll(src, target).filterKeys { it.startsWith("etc/extension/") }
        val edits = LinkedHashMap<String, String>()
        for ((rel, bytes) in files) {
            val txt = String(bytes)
            val nt = callRecordingEdit(name, txt)
            if (nt != txt) edits[rel] = nt
        }
        if (edits.isEmpty()) { log(tr("nagrywanie rozmow: $name - bez zmian (brak blokad)", "call recording: $name - unchanged (no blocks)")); return null }
        if (src.canonicalPath != out.canonicalPath) copyImage(src, out)
        ErofsPatcher(out).use { p -> edits.forEach { (rel, txt) -> p.putFile(rel, txt.toByteArray()) }; p.commit() }
        log(tr("nagrywanie rozmow: $name - ${edits.keys.joinToString()}", "call recording: $name - ${edits.keys.joinToString()}"))
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
        /** secilc telefonu - ten sam program, ktorym init kompiluje polityke przy starcie. */
        const val SECILC = "/system/bin/secilc"
        const val PUB_CIL = "plat_pub_versioned.cil"
        /** Deklaracje nazw, ktore moga stac w miejscu typu (i kilka innych, zeby nie dublowac). */
        val CIL_DECL = Regex("""\((?:type|typeattribute|typealias|role|roleattribute|boolean|tunable)\s+([A-Za-z0-9_.]+)[\s)]""")
        val CIL_TYPE = Regex("""\(type\s+([A-Za-z0-9_.]+)\)""")
        val CIL_NAME = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
        val CIL_OPS = setOf("and", "or", "not", "xor", "all", "self")

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

        private val NO_RECORD_LINE = Regex("""(no_display_record|incallui\.not_support_record|incallui\.support_record_prompt)"\s*/>""")
        private val DIALER_FEATURES = listOf("com.android.phone.oplus_dialer_enable", "com.android.server.telecom.oplus_dialer_enable")
        private val MCC_BLOCKS = listOf("com.android.incallui.hide_call_record_mcc", "com.android.incallui.support_call_record_prompt_mcc")

        /** Zmiany tekstu pliku cech (linia po linii, jak sed w module); [name] = partycja. */
        fun callRecordingEdit(name: String, txt: String): String {
            val nl = if (txt.contains("\r\n")) "\r\n" else "\n"
            val lines = txt.split(nl)
            val out = ArrayList<String>(lines.size + 2)
            var skipBlock = false
            for (l in lines) {
                if (skipBlock) { if (l.contains("</app_feature>")) skipBlock = false; continue }
                when (name) {
                    "my_region" -> {
                        if (l.contains("no_display_record\"/>")) continue
                        out += l
                        if (Regex("""<extend_features>\s*$""").containsMatchIn(l))
                            for (f in DIALER_FEATURES) if (!txt.contains("\"$f\"")) out += "\t<app_feature name=\"$f\"/>"
                    }
                    "my_product" -> if (!NO_RECORD_LINE.containsMatchIn(l)) out += l
                    "my_stock" -> {
                        if (MCC_BLOCKS.any { l.contains("<app_feature name=\"$it\">") }) { skipBlock = !l.contains("</app_feature>"); continue }
                        out += l
                    }
                    else -> out += l
                }
            }
            return out.joinToString(nl)
        }

        val APP_V2_ENABLE = listOf(
            "com.android.contacts", "com.android.incallui", "com.android.mms", "com.oplus.blacklistapp",
            "com.oplus.aiassistant", "com.oplus.aimemory", "com.oplus.aipaint", "com.oplus.aiwidgets",
        )
    }
}
