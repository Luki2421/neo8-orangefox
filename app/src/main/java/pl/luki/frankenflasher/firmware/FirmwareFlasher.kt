package pl.luki.frankenflasher.firmware

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
                log("$what: blad (${e.message}) - ponowne polaczenie i proba ${attempt + 1}/3")
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
                "Drugi telefon nie odpowiada po bledzie przy $what (${e.message}). PRZERWANO. " +
                "Przytrzymaj Vol- + Power, wejdz do bootloadera i uruchom flash od nowa (nie uruchamiaj systemu)."
            )
        }
    }

    fun run(initial: Fastboot) {
        fb = initial
        // Jak FFF: wszystko w fastbootd
        if (fb.getVar("is-userspace").trim() != "yes") {
            step("Restart do fastbootd")
            fb.command("reboot-fastboot", 30_000)
            fb = reconnect(180_000)
            if (fb.getVar("is-userspace").trim() != "yes") throw FastbootException("Urzadzenie nie jest w fastbootd")
        }
        try { fb.command("snapshot-update:cancel", 60_000); log("snapshot-update: anulowano") } catch (_: Exception) {}
        try { log("fastbootd max-download-size: ${fb.maxDownloadSize() / (1 shl 20)} MB (czesci po ${Fastboot.PIECE_LIMIT / (1 shl 20)} MB)") } catch (_: Exception) {}
        val current = fb.getVar("current-slot").trim().removePrefix("_").ifEmpty { "a" }
        switching = opt.switchSlot && (current == "a" || current == "b")
        val slot = when {
            switching -> if (current == "a") "b" else "a"
            opt.bothSlots -> "a"
            else -> current
        }
        if (switching) {
            // Jak FFF TrySwitchToOppositeSlotAsync: usun -cow, set_active na drugi slot, wszystko na niego.
            step("Przelaczanie slotu $current -> $slot")
            log("== Przelaczanie slotu: $current -> $slot (jak FFF) ==")
            try {
                val cows = fb.getVarAll().mapNotNull { Regex("""is-logical:([^:]+-cow):\s*yes""").find(it)?.groupValues?.get(1) }
                for (c in cows) try { fb.command("delete-logical-partition:$c", 60_000); log("usunieto $c") } catch (_: Exception) {}
            } catch (_: Exception) {}
            fb.command("set_active:$slot", 30_000)
            log("aktywny slot: $slot - partycje wgrywane na slot $slot, slot $current zostaje nietkniety")
        } else {
            log(if (opt.bothSlots) "== Fizyczne: oba sloty, logiczne: slot a ==" else "== Slot docelowy: $slot ==")
        }

        // ---- partycje fizyczne ----
        val phys = ArrayList<Pair<DocImage, Boolean>>() // obraz, czy blad przerywa
        if (opt.flashCritical) fw["CRITICAL"].forEach { phys += it to false }
        if (opt.flashExtra) fw["EXTRA"].forEach { phys += it to false }
        if (opt.flashModem) fw["MODEM"].forEach { phys += it to false }
        fw["BOOTLOADER"].sortedBy { if (it.base.startsWith("vbmeta")) 1 else 0 }.forEach { phys += it to true }
        phys.forEachIndexed { i, (img, fatal) ->
            step("Fizyczne ${i + 1}/${phys.size}: ${img.name}")
            flashPhysical(img, slot, fatal)
        }

        val superEmpty = fw["SUPER"].firstOrNull { it.name.equals("super_empty.img", true) }
        if (opt.useSuperEmpty && superEmpty != null && !switching) {
            step("super_empty.img -> nowy uklad super")
            retry("super_empty") { it.downloadThen(superEmpty, "update-super:super:wipe", 180_000, progress) }
            log("super: uklad wyczyszczony z super_empty.img")
        } else {
            val other = if (slot == "a") "b" else "a"
            val logical = fb.getVarAll().mapNotNull { Regex("""is-logical:([^:]+):\s*yes""").find(it)?.groupValues?.get(1) }.toSet()
            for (p in logical.sorted()) {
                if (p.endsWith("-cow") || (opt.deleteOtherSlot && !switching && p.endsWith("_$other"))) {
                    try { fb.command("delete-logical-partition:$p", 60_000); log("usunieto $p") }
                    catch (e: Exception) { log("nie usunieto $p: ${e.message}") }
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
                    log("$target: ${e.message} - brak miejsca w super, usuwam partycje slotu $current")
                    freeSlot(f, current)
                    prepareLogical(f, target, size)
                }
                f.flash(target, img, progress)
            }
        }

        if (opt.wipeData) {
            // odpowiednik fastboot -w: po pustym metadata/userdata system sam je sformatuje przy starcie
            for (p in listOf("metadata", "userdata")) {
                step("Czyszczenie $p")
                try { fb.command("erase:$p", 10 * 60_000); log("wyczyszczono $p") }
                catch (e: Exception) { log("UWAGA: nie wyczyszczono $p - ${e.message}") }
            }
        }

        if (deferred.isNotEmpty()) {
            step("Restart do bootloadera (${deferred.size} partycji)")
            fb.command("reboot-bootloader", 30_000)
            fb = reconnect(180_000)
            log("== Bootloader: ${deferred.joinToString { it.second }} ==")
            for ((src, target) in deferred) {
                try { retry(target) { it.flash(target, src, progress) } }
                catch (e: Exception) {
                    if (e is DeviceHungException) throw e
                    log("UWAGA: nie wgrano $target - ${e.message}")
                }
            }
        }

        if (!switching && opt.bothSlots && current != "a") {
            try { fb.command("set_active:a", 30_000); log("aktywny slot: a") } catch (e: Exception) { log("UWAGA: set_active:a - ${e.message}") }
        }

        if (opt.rebootAtEnd) {
            step("Restart do systemu")
            fb.command("reboot", 30_000)
        }
        step("Gotowe")
        log("== FLASH ZAKONCZONY ==")
    }

    private var switching = false
    private var freedOldSlot = false

    private fun prepareLogical(f: Fastboot, target: String, size: Long) {
        if (f.isLogical(target)) f.command("resize-logical-partition:$target:$size", 180_000)
        else { f.command("create-logical-partition:$target:$size", 180_000); log("utworzono $target") }
    }

    private fun freeSlot(f: Fastboot, s: String) {
        freedOldSlot = true
        val parts = f.getVarAll().mapNotNull { Regex("""is-logical:([^:]+):\s*yes""").find(it)?.groupValues?.get(1) }
        for (p in parts.filter { it.endsWith("_$s") }) {
            try { f.command("delete-logical-partition:$p", 60_000); log("usunieto $p") } catch (_: Exception) {}
        }
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
                    log("$target zablokowana w fastbootd - wgram ja na koncu w bootloaderze")
                    continue
                }
                if (fatal) throw e
                log("UWAGA: pominieto $target - ${e.message}")
            }
        }
    }

    /** Odpowiednik --disable-verity --disable-verification: flagi AVB (offset 120, big-endian) |= 3. */
    private fun patchVbmeta(img: DocImage): ImageSource {
        val data = img.open().use { it.readBytes() }
        if (data.size < 124 || String(data, 0, 4, Charsets.US_ASCII) != "AVB0") {
            log("${img.name}: brak naglowka AVB0 - wgrywam bez zmian")
            return img
        }
        data[123] = (data[123].toInt() or 0x03).toByte()
        log("${img.name}: weryfikacja wylaczona (flagi AVB = 3)")
        return BytesImage(img.name, data)
    }
}
