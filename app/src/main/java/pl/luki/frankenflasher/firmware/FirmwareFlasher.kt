package pl.luki.frankenflasher.firmware

import pl.luki.frankenflasher.tr
import pl.luki.frankenflasher.fastboot.Fastboot
import pl.luki.frankenflasher.fastboot.FastbootException
import pl.luki.frankenflasher.fastboot.ImageSource

class FlashOptions(
    var disableVbmeta: Boolean = true,
    var flashCritical: Boolean = true,
    var flashModem: Boolean = true,
    var flashExtra: Boolean = true,
    var useSuperEmpty: Boolean = true,
    var deleteOtherSlot: Boolean = true,
    var rebootAtEnd: Boolean = true,
    var wipeData: Boolean = false,
    var bothSlots: Boolean = true,
    /** Jak FFF "SwitchSlot": set_active na drugi slot na poczatku i wszystko na ten slot. */
    var switchSlot: Boolean = true,
    /** Przy przelaczaniu: od razu usun partycje logiczne starego slotu (super nie miesci dwoch systemow). */
    var freeOldSlotFirst: Boolean = true,
    /** Super flasher: jesli w SUPER jest super.img - wszystko w bootloaderze, super jednym obrazem. */
    var useSuperImage: Boolean = true,
)

/** Flash calego folderu firmware: fizyczne w bootloaderze, logiczne w fastbootd. */
/** Drugi telefon przestal odpowiadac - dalsze flashowanie nie ma sensu. */
class DeviceHungException(msg: String) : Exception(msg)

class FirmwareFlasher(
    private val fw: FirmwareFolder,
    private val opt: FlashOptions,
    private val log: (String) -> Unit,
    private val step: (String) -> Unit,
    private val progress: (Long, Long) -> Unit,
    private val reconnect: (Long) -> Fastboot,
) {
    private lateinit var fb: Fastboot
    /** Partycje zablokowane w fastbootd (np. modem aktywnego slotu) - wgrywane potem w bootloaderze. */
    private val deferred = ArrayList<Pair<ImageSource, String>>()

    /** Jak FFF: przy bledzie USB polacz ponownie (fastbootd) i powtorz. */
    private fun <T> retry(what: String, block: (Fastboot) -> T): T {
        var last: Exception? = null
        for (attempt in 1..3) {
            try { return block(fb) } catch (e: Exception) {
                last = e
                val usb = e is java.io.IOException || (e.message ?: "").contains("USB") || (e.message ?: "").contains("timeout")
                if (e is DeviceHungException || !usb || attempt == 3) throw e
                log(tr("$what: blad (${e.message}) - ponowne polaczenie i proba ${attempt + 1}/3", "$what: error (${e.message}) - reconnecting, attempt ${attempt + 1}/3"))
                fb = reconnect(120_000)
                ensureAlive(what)
            }
        }
        throw last!!
    }

    /** Po ponownym polaczeniu sprawdz, czy fastbootd w ogole odpowiada. */
    private fun ensureAlive(what: String) {
        try { fb.getVar("product") } catch (e: Exception) {
            throw DeviceHungException(
                tr("Drugi telefon nie odpowiada po bledzie przy $what (${e.message}). PRZERWANO. ", "Second phone not responding after error at $what (${e.message}). ABORTED. ") +
                tr("Przytrzymaj Vol- + Power, wejdz do bootloadera i uruchom flash od nowa (nie uruchamiaj systemu).", "Hold Vol- + Power, enter bootloader and start the flash again (do not boot the system).")
            )
        }
    }

    fun run(initial: Fastboot) {
        fb = initial
        val superImg = fw["SUPER"].firstOrNull { it.name.equals("super.img", true) }
        if (opt.useSuperImage && superImg != null) { runSuper(superImg); return }
        // Jak FFF: wszystko w fastbootd
        if (fb.getVar("is-userspace").trim() != "yes") {
            step(tr("Restart do fastbootd", "Rebooting to fastbootd"))
            fb.command("reboot-fastboot", 30_000)
            fb = reconnect(180_000)
            if (fb.getVar("is-userspace").trim() != "yes") throw FastbootException(tr("Urzadzenie nie jest w fastbootd", "Device is not in fastbootd"))
        }
        try { fb.command("snapshot-update:cancel", 60_000); log(tr("snapshot-update: anulowano", "snapshot-update: cancelled")) } catch (_: Exception) {}
        try { log("fastbootd max-download-size: ${fb.maxDownloadSize() / (1 shl 20)} MB (" + tr("czesci po", "pieces of") + " ${Fastboot.PIECE_LIMIT / (1 shl 20)} MB)") } catch (_: Exception) {}
        val current = fb.getVar("current-slot").trim().removePrefix("_").ifEmpty { "a" }
        switching = opt.switchSlot && (current == "a" || current == "b")
        val slot = when {
            switching -> if (current == "a") "b" else "a"
            opt.bothSlots -> "a"
            else -> current
        }
        if (switching) {
            // Jak FFF TrySwitchToOppositeSlotAsync: usun -cow, set_active na drugi slot, wszystko na niego.
            step(tr("Przelaczanie slotu $current -> $slot", "Switching slot $current -> $slot"))
            log(tr("== Przelaczanie slotu: $current -> $slot (jak FFF) ==", "== Switching slot: $current -> $slot (like FFF) =="))
            try {
                val cows = fb.getVarAll().mapNotNull { Regex("""is-logical:([^:]+-cow):\s*yes""").find(it)?.groupValues?.get(1) }
                for (c in cows) try { fb.command("delete-logical-partition:$c", 60_000); log(tr("usunieto $c", "deleted $c")) } catch (_: Exception) {}
            } catch (_: Exception) {}
            fb.command("set_active:$slot", 30_000)
            if (opt.freeOldSlotFirst) {
                log(tr("aktywny slot: $slot - usuwam partycje logiczne slotu $current (miejsce w super)", "active slot: $slot - deleting logical partitions of slot $current (space in super)"))
                freeSlot(fb, current)
            } else log(tr("aktywny slot: $slot - partycje wgrywane na slot $slot, slot $current zostaje nietkniety", "active slot: $slot - flashing to slot $slot, slot $current left untouched"))
        } else {
            log(if (opt.bothSlots) tr("== Fizyczne: oba sloty, logiczne: slot a ==", "== Physical: both slots, logical: slot a ==") else tr("== Slot docelowy: $slot ==", "== Target slot: $slot =="))
        }

        // ---- partycje fizyczne ----
        val phys = ArrayList<Pair<DocImage, Boolean>>() // obraz, czy blad przerywa
        if (opt.flashCritical) fw["CRITICAL"].forEach { phys += it to false }
        if (opt.flashExtra) fw["EXTRA"].forEach { phys += it to false }
        if (opt.flashModem) fw["MODEM"].forEach { phys += it to false }
        fw["BOOTLOADER"].sortedBy { if (it.base.startsWith("vbmeta")) 1 else 0 }.forEach { phys += it to true }
        phys.forEachIndexed { i, (img, fatal) ->
            step(tr("Fizyczne ${i + 1}/${phys.size}: ${img.name}", "Physical ${i + 1}/${phys.size}: ${img.name}"))
            flashPhysical(img, slot, fatal)
        }

        val superEmpty = fw["SUPER"].firstOrNull { it.name.equals("super_empty.img", true) }
        if (opt.useSuperEmpty && superEmpty != null && !switching) {
            step(tr("super_empty.img -> nowy uklad super", "super_empty.img -> new super layout"))
            retry("super_empty") { it.downloadThen(superEmpty, "update-super:super:wipe", 180_000, progress) }
            log(tr("super: uklad wyczyszczony z super_empty.img", "super: layout wiped with super_empty.img"))
        } else {
            val other = if (slot == "a") "b" else "a"
            val logical = fb.getVarAll().mapNotNull { Regex("""is-logical:([^:]+):\s*yes""").find(it)?.groupValues?.get(1) }.toSet()
            for (p in logical.sorted()) {
                if (p.endsWith("-cow") || (opt.deleteOtherSlot && !switching && p.endsWith("_$other"))) {
                    try { fb.command("delete-logical-partition:$p", 60_000); log(tr("usunieto $p", "deleted $p")) }
                    catch (e: Exception) { log(tr("nie usunieto $p: ${e.message}", "not deleted $p: ${e.message}")) }
                }
            }
        }

        val sys = fw["SYSTEM"]
        sys.forEachIndexed { i, img ->
            step("fastbootd ${i + 1}/${sys.size}: ${img.name}")
            val target = if (img.base.endsWith("_a") || img.base.endsWith("_b")) img.base else "${img.base}_$slot"
            val size = fb.logicalSize(img)
            retry(target) { f ->
                try { prepareLogical(f, target, size) } catch (e: FastbootException) {
                    if (!switching || freedOldSlot) throw e
                    // Brak miejsca w super na oba sloty - zwolnij stary slot i sprobuj jeszcze raz
                    log(tr("$target: ${e.message} - brak miejsca w super, usuwam partycje slotu $current", "$target: ${e.message} - no space in super, deleting slot $current partitions"))
                    freeSlot(f, current)
                    prepareLogical(f, target, size)
                }
                f.flash(target, img, progress)
            }
        }

        if (opt.wipeData) {
            // odpowiednik fastboot -w: po pustym metadata/userdata system sam je sformatuje przy starcie
            for (p in listOf("metadata", "userdata")) {
                step(tr("Czyszczenie $p", "Wiping $p"))
                try { fb.command("erase:$p", 10 * 60_000); log(tr("wyczyszczono $p", "wiped $p")) }
                catch (e: Exception) { log(tr("UWAGA: nie wyczyszczono $p - ${e.message}", "WARNING: $p not wiped - ${e.message}")) }
            }
        }

        if (deferred.isNotEmpty()) {
            step(tr("Restart do bootloadera (${deferred.size} partycji)", "Rebooting to bootloader (${deferred.size} partitions)"))
            fb.command("reboot-bootloader", 30_000)
            fb = reconnect(180_000)
            log("== Bootloader: ${deferred.joinToString { it.second }} ==")
            for ((src, target) in deferred) {
                try { retry(target) { it.flash(target, src, progress) } }
                catch (e: Exception) {
                    if (e is DeviceHungException) throw e
                    log(tr("UWAGA: nie wgrano $target - ${e.message}", "WARNING: $target not flashed - ${e.message}"))
                }
            }
        }

        if (!switching && opt.bothSlots && current != "a") {
            try { fb.command("set_active:a", 30_000); log(tr("aktywny slot: a", "active slot: a")) } catch (e: Exception) { log("UWAGA: set_active:a - ${e.message}") }
        }

        if (opt.rebootAtEnd) {
            step(tr("Restart do systemu", "Rebooting to system"))
            fb.command("reboot", 30_000)
        }
        step(tr("Gotowe", "Done"))
        log(tr("== FLASH ZAKONCZONY ==", "== FLASH COMPLETE =="))
    }

    private var switching = false

    /**
     * Super flasher jak w FFF: wszystko w fastbootd - partycje fizyczne na oba sloty,
     * na koniec super.img (slot a). Zablokowane w fastbootd (np. modem aktywnego slotu) - w bootloaderze.
     */
    private fun runSuper(superImg: DocImage) {
        if (fb.getVar("is-userspace").trim() != "yes") {
            step(tr("Restart do fastbootd", "Rebooting to fastbootd"))
            fb.command("reboot-fastboot", 30_000)
            fb = reconnect(180_000)
            if (fb.getVar("is-userspace").trim() != "yes") throw FastbootException(tr("Urzadzenie nie jest w fastbootd", "Device is not in fastbootd"))
        }
        try { fb.command("snapshot-update:cancel", 60_000); log(tr("snapshot-update: anulowano", "snapshot-update: cancelled")) } catch (_: Exception) {}
        log(tr("== Super flasher (jak FFF): fastbootd, partycje fizyczne na oba sloty, na koniec super.img ==",
            "== Super flasher (like FFF): fastbootd, physical partitions to both slots, super.img last =="))
        try {
            val dev = fb.getVar("partition-size:super").trim()
            val devSize = if (dev.startsWith("0x", true)) dev.substring(2).toLong(16) else dev.toLong()
            val imgSize = superImg.open().use { i ->
                val h = ByteArray(28); var n = 0
                while (n < 28) { val r = i.read(h, n, 28 - n); if (r < 0) break; n += r }
                val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                (bb.getInt(16).toLong() and 0xFFFFFFFFL) * bb.getInt(12)
            }
            log("super: " + tr("telefon", "phone") + " ${devSize / (1 shl 20)} MB, super.img ${imgSize / (1 shl 20)} MB")
            if (imgSize != devSize) throw FastbootException(tr(
                "Rozmiar super.img (${imgSize}) rozny od partycji super w telefonie (${devSize}) - to OTA innego modelu?",
                "super.img size (${imgSize}) differs from the phone's super partition (${devSize}) - OTA for another model?"))
        } catch (e: FastbootException) { throw e } catch (_: Exception) {}
        val phys = ArrayList<Pair<DocImage, Boolean>>()
        if (opt.flashCritical) fw["CRITICAL"].forEach { phys += it to false }
        if (opt.flashExtra) fw["EXTRA"].forEach { phys += it to false }
        if (opt.flashModem) fw["MODEM"].forEach { phys += it to false }
        fw["BOOTLOADER"].sortedBy { if (it.base.startsWith("vbmeta")) 1 else 0 }.forEach { phys += it to true }
        switching = false
        phys.forEachIndexed { i, (img, fatal) ->
            step(tr("Fizyczne ${i + 1}/${phys.size}: ${img.name}", "Physical ${i + 1}/${phys.size}: ${img.name}"))
            flashPhysical(img, "a", fatal)
        }
        step("super.img")
        retry("super") { it.flash("super", superImg, progress) }
        if (deferred.isNotEmpty()) {
            step(tr("Restart do bootloadera (${deferred.size} partycji)", "Rebooting to bootloader (${deferred.size} partitions)"))
            fb.command("reboot-bootloader", 30_000)
            fb = reconnect(180_000)
            log("== Bootloader: ${deferred.joinToString { it.second }} ==")
            for ((src, target) in deferred) {
                try { retry(target) { it.flash(target, src, progress) } }
                catch (e: Exception) { if (e is DeviceHungException) throw e; log(tr("UWAGA: nie wgrano $target - ${e.message}", "WARNING: $target not flashed - ${e.message}")) }
            }
        }
        try { fb.command("set_active:a", 30_000); log(tr("aktywny slot: a", "active slot: a")) } catch (e: Exception) { log("set_active:a - ${e.message}") }
        if (opt.wipeData) for (p in listOf("metadata", "userdata")) {
            step(tr("Czyszczenie $p", "Wiping $p"))
            try { fb.command("erase:$p", 10 * 60_000); log(tr("wyczyszczono $p", "wiped $p")) }
            catch (e: Exception) { log(tr("UWAGA: nie wyczyszczono $p - ${e.message}", "WARNING: $p not wiped - ${e.message}")) }
        }
        if (opt.rebootAtEnd) { step(tr("Restart do systemu", "Rebooting to system")); fb.command("reboot", 30_000) }
        step(tr("Gotowe", "Done"))
        log(tr("== FLASH ZAKONCZONY ==", "== FLASH COMPLETE =="))
    }

    private var freedOldSlot = false

    private fun prepareLogical(f: Fastboot, target: String, size: Long) {
        if (f.isLogical(target)) f.command("resize-logical-partition:$target:$size", 180_000)
        else { f.command("create-logical-partition:$target:$size", 180_000); log(tr("utworzono $target", "created $target")) }
    }

    private fun freeSlot(f: Fastboot, s: String) {
        freedOldSlot = true
        val parts = f.getVarAll().mapNotNull { Regex("""is-logical:([^:]+):\s*yes""").find(it)?.groupValues?.get(1) }
        val del = ArrayList<String>()
        for (p in parts.filter { it.endsWith("_$s") }) {
            try { f.command("delete-logical-partition:$p", 60_000); del += p } catch (_: Exception) {}
        }
        log(tr("usunieto ${del.size} partycji slotu $s", "deleted ${del.size} partitions of slot $s") + if (del.isNotEmpty()) ": ${del.joinToString()}" else "")
    }

    private fun flashPhysical(img: DocImage, slot: String, fatal: Boolean) {
        val b = img.base
        val targets = when {
            b.endsWith("_a") || b.endsWith("_b") -> listOf(b)
            fb.hasSlot(b) -> if (opt.bothSlots && !switching) listOf("${b}_a", "${b}_b") else listOf("${b}_$slot")
            else -> listOf(b)
        }
        val src: ImageSource = if (opt.disableVbmeta && b.startsWith("vbmeta")) patchVbmeta(img) else img
        for (target in targets) {
            try {
                retry(target) { it.flash(target, src, progress) }
            } catch (e: Exception) {
                if (e is DeviceHungException) throw e
                if ((e.message ?: "").contains("not permitted", true)) {
                    deferred += src to target
                    log(tr("$target zablokowana w fastbootd - wgram ja na koncu w bootloaderze", "$target locked in fastbootd - will flash it in bootloader at the end"))
                    continue
                }
                if (fatal) throw e
                log(tr("UWAGA: pominieto $target - ${e.message}", "WARNING: skipped $target - ${e.message}"))
            }
        }
    }

    /** Odpowiednik --disable-verity --disable-verification: flagi AVB (offset 120, big-endian) |= 3. */
    private fun patchVbmeta(img: DocImage): ImageSource {
        val data = img.open().use { it.readBytes() }
        if (data.size < 124 || String(data, 0, 4, Charsets.US_ASCII) != "AVB0") {
            log(tr("${img.name}: brak naglowka AVB0 - wgrywam bez zmian", "${img.name}: no AVB0 header - flashing unchanged"))
            return img
        }
        data[123] = (data[123].toInt() or 0x03).toByte()
        log(tr("${img.name}: weryfikacja wylaczona (flagi AVB = 3)", "${img.name}: verification disabled (AVB flags = 3)"))
        return BytesImage(img.name, data)
    }
}
