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
)

/** Flash calego folderu firmware: fizyczne w bootloaderze, logiczne w fastbootd. */
class FirmwareFlasher(
    private val fw: FirmwareFolder,
    private val opt: FlashOptions,
    private val log: (String) -> Unit,
    private val step: (String) -> Unit,
    private val progress: (Long, Long) -> Unit,
    private val reconnect: (Long) -> Fastboot,
) {
    private lateinit var fb: Fastboot

    /** Jak FFF: przy bledzie USB polacz ponownie (fastbootd) i powtorz. */
    private fun <T> retry(what: String, block: (Fastboot) -> T): T {
        var last: Exception? = null
        for (attempt in 1..3) {
            try { return block(fb) } catch (e: Exception) {
                last = e
                val usb = e is java.io.IOException || (e.message ?: "").contains("USB") || (e.message ?: "").contains("timeout")
                if (!usb || attempt == 3) throw e
                log("$what: blad (${e.message}) - ponowne polaczenie i proba ${attempt + 1}/3")
                fb = reconnect(120_000)
            }
        }
        throw last!!
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
        val current = fb.getVar("current-slot").trim().removePrefix("_").ifEmpty { "a" }
        val slot = if (opt.bothSlots) "a" else current
        log(if (opt.bothSlots) "== Fizyczne: oba sloty, logiczne: slot a (jak FFF) ==" else "== Slot docelowy: $slot ==")

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
        if (opt.useSuperEmpty && superEmpty != null) {
            step("super_empty.img -> nowy uklad super")
            retry("super_empty") { it.downloadThen(superEmpty, "update-super:super:wipe", 180_000, progress) }
            log("super: uklad wyczyszczony z super_empty.img")
        } else {
            val other = if (slot == "a") "b" else "a"
            val logical = fb.getVarAll().mapNotNull { Regex("""is-logical:([^:]+):\s*yes""").find(it)?.groupValues?.get(1) }.toSet()
            for (p in logical.sorted()) {
                if (p.endsWith("-cow") || (opt.deleteOtherSlot && p.endsWith("_$other"))) {
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
                if (f.isLogical(target)) f.command("resize-logical-partition:$target:$size", 180_000)
                else { f.command("create-logical-partition:$target:$size", 180_000); log("utworzono $target") }
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

        if (opt.bothSlots && current != "a") {
            try { fb.command("set_active:a", 30_000); log("aktywny slot: a") } catch (e: Exception) { log("UWAGA: set_active:a - ${e.message}") }
        }

        if (opt.rebootAtEnd) {
            step("Restart do systemu")
            fb.command("reboot", 30_000)
        }
        step("Gotowe")
        log("== FLASH ZAKONCZONY ==")
    }

    private fun flashPhysical(img: DocImage, slot: String, fatal: Boolean) {
        val b = img.base
        val targets = when {
            b.endsWith("_a") || b.endsWith("_b") -> listOf(b)
            fb.hasSlot(b) -> if (opt.bothSlots) listOf("${b}_a", "${b}_b") else listOf("${b}_$slot")
            else -> listOf(b)
        }
        val src: ImageSource = if (opt.disableVbmeta && b.startsWith("vbmeta")) patchVbmeta(img) else img
        for (target in targets) {
            try {
                retry(target) { it.flash(target, src, progress) }
            } catch (e: Exception) {
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
