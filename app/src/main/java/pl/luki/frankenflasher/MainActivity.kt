package pl.luki.frankenflasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import java.io.File
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import pl.luki.frankenflasher.fastboot.Fastboot
import pl.luki.frankenflasher.fastboot.FastbootException
import pl.luki.frankenflasher.firmware.FirmwareFlasher
import pl.luki.frankenflasher.firmware.FirmwareFolder
import pl.luki.frankenflasher.firmware.FlashOptions
import pl.luki.frankenflasher.ota.OtaExtractor
import pl.luki.frankenflasher.fastboot.ImageSource
import pl.luki.frankenflasher.usb.UsbFastbootTransport
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        private const val ACTION_PERM = "pl.luki.frankenflasher.USB_PERMISSION"
        private const val REQ_IMAGE = 10
        private const val REQ_FIRMWARE = 11
        private const val REQ_OTA = 12
        private const val REQ_OTA_OUT = 13
        private const val REQ_SAVE_LOG = 14
        private const val REQ_EROFS_TEST = 15
        private const val REQ_FR_BASE = 16
        private const val REQ_FR_DONOR = 17
    }

    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val exec = Executors.newSingleThreadExecutor()
    private var transport: UsbFastbootTransport? = null
    private var fb: Fastboot? = null

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var partEdit: EditText
    private lateinit var status: TextView
    private lateinit var stepView: TextView
    private lateinit var fwSummary: TextView
    private var firmware: FirmwareFolder? = null
    private var otaUri: Uri? = null
    private var otaName: String = ""
    private var otaOut: Uri? = null
    private lateinit var otaView: TextView
    private val opt = FlashOptions()
    @Volatile private var permLatch: CountDownLatch? = null
    @Volatile private var busy = false
    private var logFile: java.io.File? = null
    private val logLock = Any()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev = intent.usbDevice()
            when (intent.action) {
                ACTION_PERM -> {
                    val l = permLatch
                    if (l != null) l.countDown()
                    else if (busy) Unit
                    else if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) openDevice(dev)
                    else log(tr("Brak zgody na dostep USB", "USB permission denied"))
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev != null && dev.deviceName == transport?.device?.deviceName && !busy) {
                        log(tr("Urzadzenie odlaczone", "Device disconnected"))
                        closeDevice()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.getString("lang", null)?.let { I18n.en = it == "en" }
        UsbFastbootTransport.onAsyncDisabled = { log(tr("USB: szybki zapis wylaczony ($it) - zwykly tryb", "USB: fast write disabled ($it) - normal mode")) }
        UsbFastbootTransport.onChunkDowngrade = { log(tr("USB: kontroler odrzucil duza paczke, przechodze na ${it / 1024} KB", "USB: controller rejected large packet, switching to ${it / 1024} KB")) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logFile = try {
            val dir = java.io.File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            java.io.File(dir, "log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
        } catch (_: Exception) { null }
        buildUi()
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        log("Franken Flasher ${BuildConfigCompat.version(this)} - fastboot + firmware + OTA + Franken")
        log(tr("Podlacz drugi telefon w trybie fastboot (lub fastbootd) i nacisnij Polacz.", "Connect the second phone in fastboot (or fastbootd) mode and tap Connect."))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (busy) return // w trakcie operacji polaczeniem zarzadza sama operacja
        i.usbDevice()?.let { requestOrOpen(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        closeDevice()
        exec.shutdownNow()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { text = tr("Nie polaczono  (v", "Not connected  (v") + BuildConfigCompat.version(this@MainActivity) + ")"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(status)

        // --- zakladki ---
        val tabNames = listOf("Fastboot", "OTA", "Firmware", "Franken")
        val pages = tabNames.map { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
        val pageScroll = ScrollView(this)
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val tabButtons = ArrayList<Button>()
        fun showTab(i: Int) {
            pageScroll.removeAllViews()
            pageScroll.addView(pages[i])
            pageScroll.scrollTo(0, 0)
            tabButtons.forEachIndexed { k, btn -> btn.alpha = if (k == i) 1f else 0.5f }
        }
        tabNames.forEachIndexed { i, n ->
            val btn = Button(this).apply { text = n; isAllCaps = false; setOnClickListener { showTab(i) } }
            tabButtons += btn
            tabBar.addView(btn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(tabBar)

        fun row(page: LinearLayout, vararg b: Pair<String, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            b.forEach { (label, action) ->
                r.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            page.addView(r)
        }
        fun header(page: LinearLayout, t: String) =
            page.addView(TextView(this).apply { text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, 0) })
        fun chk(label: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            CheckBox(this).apply { text = label; isChecked = get(); textSize = 13f; setOnCheckedChangeListener { _, c -> set(c) } }

        // Strona 1: Fastboot
        val p1 = pages[0]
        row(p1, tr("Polacz", "Connect") to ::connect, "Info" to ::info, "getvar all" to ::getvarAll)
        row(p1, "-> fastbootd" to { simple("reboot-fastboot", true) },
            "-> bootloader" to { simple("reboot-bootloader", true) },
            "-> system" to { simple("reboot", true) })
        row(p1, (if (I18n.en) "Polski" else "English") to {
            I18n.en = !I18n.en
            prefs.edit().putString("lang", if (I18n.en) "en" else "pl").apply()
            recreate()
        })
        row(p1, "Slot A" to { simple("set_active:a", false) }, "Slot B" to { simple("set_active:b", false) })
        row(p1, tr("Wyczysc dane (format)", "Wipe data (format)") to { confirmWipe() })
        header(p1, tr("Pojedynczy obraz", "Single image"))
        partEdit = EditText(this).apply { hint = tr("partycja (puste = z nazwy pliku, np. boot_a)", "partition (empty = from file name, e.g. boot_a)"); isSingleLine = true }
        p1.addView(partEdit)
        row(p1, tr("Wybierz obraz i flashuj", "Pick image and flash") to ::pickImage)

        // Strona 2: OTA
        val p2 = pages[1]
        header(p2, tr("Rozpakuj OTA (zip / payload.bin)", "Extract OTA (zip / payload.bin)"))
        otaView = TextView(this).apply { text = "OTA: -   " + tr("cel", "target") + ": -"; textSize = 13f }
        p2.addView(otaView)
        row(p2, tr("Wybierz OTA", "Pick OTA") to ::pickOta, "Cel" to ::pickOtaOut)
        row(p2, tr("Rozpakuj", "Extract") to ::extractOta)
        row(p2, tr("Sprawdz sumy folderu z OTA", "Verify folder checksums against OTA") to ::verifyOta)

        // Strona 3: Firmware
        val p3 = pages[2]
        header(p3, tr("Firmware (folder jak w FFF)", "Firmware (FFF-style folder)"))
        fwSummary = TextView(this).apply { text = tr("Nie wybrano folderu", "No folder selected"); textSize = 13f }
        p3.addView(fwSummary)
        row(p3, tr("Wybierz folder", "Pick folder") to ::pickFirmware)
        p3.addView(chk(tr("przelacz na drugi slot i wgraj na niego (jak FFF)", "switch to the other slot and flash it (like FFF)"), { opt.switchSlot }, { opt.switchSlot = it }))
        p3.addView(chk(tr("od razu usun system starego slotu (szybciej)", "delete old slot system right away (faster)"), { opt.freeOldSlotFirst }, { opt.freeOldSlotFirst = it }))
        p3.addView(chk(tr("bez przelaczania: oba sloty (fizyczne a+b, logiczne a)", "no switching: both slots (physical a+b, logical a)"), { opt.bothSlots }, { opt.bothSlots = it }))
        p3.addView(chk(tr("vbmeta off (wylacz weryfikacje)", "vbmeta off (disable verification)"), { opt.disableVbmeta }, { opt.disableVbmeta = it }))
        p3.addView(chk("CRITICAL + EXTRA", { opt.flashCritical }, { opt.flashCritical = it; opt.flashExtra = it }))
        p3.addView(chk("MODEM", { opt.flashModem }, { opt.flashModem = it }))
        p3.addView(chk(tr("super_empty.img (jesli jest)", "super_empty.img (if present)"), { opt.useSuperEmpty }, { opt.useSuperEmpty = it }))
        p3.addView(chk(tr("usun partycje 2. slotu z super", "delete other-slot partitions from super"), { opt.deleteOtherSlot }, { opt.deleteOtherSlot = it }))
        p3.addView(chk(tr("restart do systemu na koniec", "reboot to system at the end"), { opt.rebootAtEnd }, { opt.rebootAtEnd = it }))
        p3.addView(chk(tr("wyczysc dane (userdata + metadata)", "wipe data (userdata + metadata)"), { opt.wipeData }, { opt.wipeData = it }).apply {
            setTextColor(android.graphics.Color.rgb(255, 120, 110))
        })
        row(p3, tr("Flashuj firmware", "Flash firmware") to ::flashFirmware)

        val p4 = pages[3]
        header(p4, tr("Franken: baza (sprzet) + dawca (system)", "Franken: base (hardware) + donor (system)"))
        frBaseView = TextView(this).apply { text = "Baza: -" }
        frDonorView = TextView(this).apply { text = "Dawca: -" }
        p4.addView(frBaseView); p4.addView(frDonorView)
        row(p4, tr("Baza (Neo 8)", "Base (Neo 8)") to { pickTree(REQ_FR_BASE) }, tr("Dawca (GT 8 Pro)", "Donor (GT 8 Pro)") to { pickTree(REQ_FR_DONOR) })
        row(p4, tr("Partycje: baza / dawca", "Partitions: base / donor") to ::choosePartitions)
        p4.addView(chk(tr("my_product: konfiguracja sprzetu z bazy", "my_product: hardware config from base"), { frOpt.patchMyProduct }, { frOpt.patchMyProduct = it }))
        p4.addView(chk(tr("wibracje z bazy (bez qcom_lmvibrator)", "vibration from base (no qcom_lmvibrator)"), { frOpt.fixVibration }, { frOpt.fixVibration = it }))
        p4.addView(chk(tr("nakladka wyswietlacza z bazy", "display overlay from base"), { frOpt.displayOverlayFromBase }, { frOpt.displayOverlayFromBase = it }))
        p4.addView(chk(tr("my_manifest: wersja dawcy", "my_manifest: donor version"), { frOpt.patchMyManifest }, { frOpt.patchMyManifest = it }))
        p4.addView(chk(tr("odm: producent/marka dawcy", "odm: donor manufacturer/brand"), { frOpt.patchOdmIdentity }, { frOpt.patchOdmIdentity = it }))
        p4.addView(chk(tr("app_v2: odblokuj Telefon/Kontakty/SMS", "app_v2: unlock Phone/Contacts/SMS"), { frOpt.unlockAppV2 }, { frOpt.unlockAppV2 = it }))
        p4.addView(chk(tr("dzwonki z haptic_ringtone do product", "ringtones from haptic_ringtone to product"), { frOpt.ringtones }, { frOpt.ringtones = it }))
        p4.addView(chk(tr("pomin my_preload", "skip my_preload"), { frOpt.skipMyPreload }, { frOpt.skipMyPreload = it }))
        row(p4, tr("1. Zbuduj Franken", "1. Build Franken") to ::buildFranken, tr("2. Flashuj Franken", "2. Flash Franken") to ::flashFranken)
        header(p4, tr("Narzedzia / testy", "Tools / tests"))
        row(p4, tr("Dostep do plikow", "File access") to ::requestAllFiles, tr("Test narzedzi EROFS", "Test EROFS tools") to ::testErofsTools)
        row(p4, tr("Test: wypakuj obraz EROFS", "Test: extract EROFS image") to {
            erofsWriteTest = false
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_EROFS_TEST)
        })
        row(p4, tr("Test: zapis do kopii obrazu", "Test: write to image copy") to {
            erofsWriteTest = true
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_EROFS_TEST)
        })

        root.addView(pageScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.1f))

        // --- postep + log (zawsze widoczne, przewijane) ---
        stepView = TextView(this).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
        root.addView(stepView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress)
        val logBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        logBar.addView(TextView(this).apply { text = "Log"; textSize = 13f; setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        logBar.addView(Button(this).apply { text = tr("Zapisz", "Save"); isAllCaps = false; textSize = 12f; setOnClickListener { saveLog() } })
        logBar.addView(Button(this).apply { text = tr("Udostepnij", "Share"); isAllCaps = false; textSize = 12f; setOnClickListener { shareLog() } })
        logBar.addView(Button(this).apply { text = tr("Wyczysc", "Clear"); isAllCaps = false; textSize = 12f; setOnClickListener { logView.text = "" } })
        root.addView(logBar)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView); setBackgroundColor(android.graphics.Color.rgb(24, 24, 24)) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        showTab(0)
        restoreState()
        // Android 15+ rysuje pod paskami systemowymi - odsun tresc o ich wysokosc
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(pad, pad + ins.systemWindowInsetTop, pad, pad + ins.systemWindowInsetBottom)
            ins
        }
        setContentView(root)
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun log(msg: String) {
        val line = synchronized(logLock) {
            val l = "${timeFmt.format(Date())} $msg\n"
            try { logFile?.appendText(l) } catch (_: Exception) {}
            l
        }
        runOnUiThread { appendLogLine(line) }
    }

    private fun appendLogLine(line: String) {
        logView.append(line)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    @Suppress("DEPRECATION")
    private fun saveLog() {
        val name = logFile?.name ?: "franken-flasher-log.txt"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE_LOG)
    }

    private fun shareLog() {
        val text = logView.text.toString().takeLast(400_000)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Franken Flasher log").putExtra(Intent.EXTRA_TEXT, text), tr("Udostepnij log", "Share log")))
    }

    private fun setProgress(done: Long, total: Long) = runOnUiThread {
        progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
    }

    // ---------------- USB ----------------

    private fun connect() {
        if (fb != null) { log(tr("Juz polaczono: ${transport?.device?.productName}", "Already connected: ${transport?.device?.productName}")); return }
        val dev = usb.deviceList.values.firstOrNull { UsbFastbootTransport.findInterface(it) != null }
        if (dev == null) { log(tr("Nie znaleziono urzadzenia fastboot. Sprawdz kabel OTG i tryb fastboot.", "No fastboot device found. Check the OTG cable and fastboot mode.")); return }
        requestOrOpen(dev)
    }

    private fun requestOrOpen(dev: UsbDevice) {
        if (usb.hasPermission(dev)) { openDevice(dev); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
        usb.requestPermission(dev, pi)
        log(tr("Prosba o dostep do USB...", "Requesting USB access..."))
    }

    private fun openDevice(dev: UsbDevice, showInfo: Boolean = true) {
        closeDevice()
        try {
            val t = UsbFastbootTransport.open(usb, dev)
            transport = t
            fb = Fastboot(t, ::log)
            log(tr("Polaczono: ${dev.manufacturerName ?: ""} ${dev.productName ?: ""} (${dev.deviceName})", "Connected: ${dev.manufacturerName ?: ""} ${dev.productName ?: ""} (${dev.deviceName})"))
            runOnUiThread { status.text = tr("Polaczono: ${dev.productName ?: dev.deviceName}  (v", "Connected: ${dev.productName ?: dev.deviceName}  (v") + BuildConfigCompat.version(this) + ")" }
            if (showInfo) info()
        } catch (e: Exception) {
            log(tr("Blad polaczenia: ${e.message}", "Connection error: ${e.message}"))
        }
    }

    private fun closeDevice() {
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        fb = null
        runOnUiThread { status.text = tr("Nie polaczono  (v", "Not connected  (v") + BuildConfigCompat.version(this) + ")" }
    }

    private fun withFb(block: (Fastboot) -> Unit) {
        val f = fb ?: run { log(tr("Najpierw nacisnij Polacz", "Tap Connect first")); return }
        exec.execute {
            busy = true
            try { block(f) } catch (e: Exception) { log("BLAD: ${e.message}") } finally { busy = false }
        }
    }

    /** Czeka, az urzadzenie wroci po restarcie (fastboot/fastbootd) i laczy sie ponownie. Wywolywac z watku roboczego. */
    private fun reconnectBlocking(timeoutMs: Long): Fastboot {
        closeDevice()
        log(tr("Czekam na ponowne polaczenie...", "Waiting for reconnection..."))
        Thread.sleep(4000)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val dev = usb.deviceList.values.firstOrNull { UsbFastbootTransport.findInterface(it) != null }
            if (dev != null) {
                if (!usb.hasPermission(dev)) {
                    val l = CountDownLatch(1)
                    permLatch = l
                    runOnUiThread { requestOrOpen(dev) }
                    l.await(90, TimeUnit.SECONDS)
                    permLatch = null
                }
                if (usb.hasPermission(dev)) {
                    openDevice(dev, false)
                    fb?.let { return it }
                }
            }
            Thread.sleep(1500)
        }
        throw FastbootException(tr("Urzadzenie nie wrocilo po restarcie", "Device did not come back after reboot"))
    }

    private fun setStep(s: String) = runOnUiThread { stepView.text = s }

    @Suppress("DEPRECATION")
    private fun pickFirmware() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FIRMWARE)
    }

    private val prefs by lazy { getSharedPreferences("ff", MODE_PRIVATE) }

    /** Przywraca foldery i ostatni zbudowany Franken z poprzedniego uruchomienia. */
    private fun restoreState() {
        prefs.getString("fwTree", null)?.let { u ->
            val uri = Uri.parse(u)
            if (contentResolver.persistedUriPermissions.any { it.uri == uri }) loadFirmware(uri, save = false)
        }
        prefs.getString("frBase", null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { frBase = it; frBaseView.text = "Baza: ${it.path}" }
        prefs.getString("frDonor", null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { frDonor = it; frDonorView.text = "Dawca: ${it.path}" }
        prefs.getString("frFromBase", null)?.let { v -> frOpt.fromBase = v.split(',').filter { it.isNotBlank() }.toMutableSet() }
        prefs.getString("frResult", null)?.let { txt ->
            val groups = LinkedHashMap<String, MutableList<pl.luki.frankenflasher.firmware.DocImage>>()
            var ok = true
            txt.lines().filter { it.contains('|') }.forEach { l ->
                val (g, path) = l.split('|', limit = 2)
                val f = File(path)
                if (!f.isFile) ok = false else groups.getOrPut(g) { ArrayList() } += pl.luki.frankenflasher.firmware.DocImage(f)
            }
            if (ok && groups.isNotEmpty()) {
                frResult = FirmwareFolder(groups)
                log(tr("Ostatni Franken gotowy do flashowania (bez ponownego budowania):\n", "Last Franken ready to flash (no rebuild needed):\n") + frResult!!.summary())
            }
        }
    }

    private fun saveFranken(r: pl.luki.frankenflasher.franken.Franken.Result?) {
        val e = prefs.edit()
        frBase?.let { e.putString("frBase", it.path) }
        frDonor?.let { e.putString("frDonor", it.path) }
        e.putString("frFromBase", frOpt.fromBase.joinToString(","))
        if (r != null) e.putString("frResult", r.groups.flatMap { (g, l) -> l.map { "$g|${it.path}" } }.joinToString("\n"))
        else e.remove("frResult")
        e.apply()
    }

    private fun loadFirmware(tree: Uri, save: Boolean = true) {
        if (save) prefs.edit().putString("fwTree", tree.toString()).apply()
        try { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        exec.execute {
            try {
                val fw = FirmwareFolder.scan(this, tree)
                firmware = fw
                runOnUiThread { fwSummary.text = fw.summary() }
                log(tr("Folder firmware:\n", "Firmware folder:\n") + fw.summary())
                if (fw.groups.isEmpty()) log(tr("Brak podfolderow BOOTLOADER/CRITICAL/SYSTEM... - wybierz glowny folder firmware", "No BOOTLOADER/CRITICAL/SYSTEM... subfolders - pick the main firmware folder"))
            } catch (e: Exception) { log(tr("Blad odczytu folderu: ${e.message}", "Folder read error: ${e.message}")) }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickOta() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_OTA)
    }

    @Suppress("DEPRECATION")
    private fun pickOtaOut() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OTA_OUT) }

    private fun updateOtaView() = runOnUiThread {
        otaView.text = "OTA: ${otaName.ifEmpty { "-" }}\n" + tr("cel", "target") + ": ${otaOut?.lastPathSegment?.substringAfterLast(':') ?: "-"}"
    }

    private fun extractOta() {
        val inUri = otaUri ?: run { log(tr("Najpierw wybierz plik OTA", "Pick an OTA file first")); return }
        val out = otaOut ?: run { log(tr("Najpierw wybierz folder docelowy", "Pick a target folder first")); return }
        exec.execute {
            try {
                setStep(tr("Rozpakowywanie $otaName...", "Extracting $otaName..."))
                log(tr("Rozpakowywanie $otaName", "Extracting $otaName"))
                val t0 = System.currentTimeMillis()
                val r = OtaExtractor(this, ::log, ::setProgress).extract(inUri, otaName, out)
                val fw = FirmwareFolder.scan(this, r.tree, r.folderDocId)
                firmware = fw
                runOnUiThread { fwSummary.text = fw.summary() }
                log(tr("Gotowe w ${(System.currentTimeMillis() - t0) / 1000} s. Folder ustawiony do flashowania:\n", "Done in ${(System.currentTimeMillis() - t0) / 1000} s. Folder set for flashing:\n") + fw.summary())
                setStep(tr("Rozpakowano - mozna flashowac", "Extracted - ready to flash"))
            } catch (e: Exception) {
                log(tr("BLAD rozpakowania: ${e.message ?: e}", "EXTRACTION ERROR: ${e.message ?: e}"))
                setStep(tr("BLAD rozpakowania", "EXTRACTION ERROR"))
            } finally { setProgress(0, 0) }
        }
    }

    private fun verifyOta() {
        val inUri = otaUri ?: run { log(tr("Najpierw wybierz plik OTA", "Pick an OTA file first")); return }
        val fw = firmware ?: run { log(tr("Najpierw wybierz folder firmware (sekcja Firmware)", "Pick a firmware folder first (Firmware tab)")); return }
        exec.execute {
            try {
                setStep(tr("Sprawdzanie sum...", "Verifying checksums..."))
                val images = fw.groups.values.flatten().filter { it.uri != null }.associate { it.base to it.uri!! }
                val bad = OtaExtractor(this, ::log, ::setProgress).verify(inUri, otaName, images)
                if (bad.isEmpty()) { log(tr("Wszystkie sumy SHA-256 zgodne", "All SHA-256 checksums match")); setStep(tr("Sumy OK - mozna flashowac", "Checksums OK - ready to flash")) }
                else { bad.forEach { log("BLAD: $it") }; setStep(tr("BLEDNE SUMY (${bad.size}) - nie flashuj", "BAD CHECKSUMS (${bad.size}) - do not flash")) }
            } catch (e: Exception) {
                log(tr("BLAD sprawdzania: ${e.message ?: e}", "VERIFY ERROR: ${e.message ?: e}")); setStep(tr("BLAD sprawdzania", "VERIFY ERROR"))
            } finally { setProgress(0, 0) }
        }
    }

    private fun flashFirmware() {
        val fw = firmware ?: run { log(tr("Najpierw wybierz folder firmware", "Pick a firmware folder first")); return }
        flashFolder(fw, "Flash firmware")
    }

    private fun flashFolder(fw: FirmwareFolder, title: String) {
        if (fb == null) { log(tr("Najpierw nacisnij Polacz", "Tap Connect first")); return }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(fw.summary() + tr("\n\nWgrac na podlaczony telefon (", "\n\nFlash to the connected phone (") + (if (opt.switchSlot) tr("przelaczenie na drugi slot", "switch to the other slot") else if (opt.bothSlots) tr("oba sloty", "both slots") else tr("aktualny slot", "current slot")) + tr(")? Wszystko w fastbootd. Nie odlaczaj kabla do konca.", ")? Everything in fastbootd. Do not unplug the cable until done.") +
                (if (opt.wipeData) tr("\n\nUWAGA: WSZYSTKIE DANE na telefonie (zdjecia, aplikacje, konta) zostana USUNIETE.", "\n\nWARNING: ALL DATA on the phone (photos, apps, accounts) will be ERASED.") else ""))
            .setPositiveButton("Flash") { _, _ ->
                withFb { f ->
                    try {
                        FirmwareFlasher(fw, opt, ::log, ::setStep, ::setProgress, ::reconnectBlocking).run(f)
                    } catch (e: Exception) {
                        setStep("BLAD: ${e.message}")
                        throw e
                    } finally { setProgress(0, 0) }
                }
            }
            .setNegativeButton(tr("Anuluj", "Cancel"), null)
            .show()
    }

    // ---------------- Komendy ----------------

    private fun info() = withFb { f ->
        for (v in listOf("product", "current-slot", "is-userspace", "unlocked", "secure", "max-download-size", "version-bootloader")) {
            val r = try { f.getVar(v) } catch (e: Exception) { "-" }
            log("  $v: $r")
        }
    }

    private fun getvarAll() = withFb { f -> f.getVarAll().forEach { log("  $it") } }

    private fun simple(cmd: String, reboot: Boolean) = withFb { f ->
        log("> $cmd")
        val r = f.command(cmd, 30_000)
        log("OKAY $r")
        if (reboot) {
            log(tr("Urzadzenie sie restartuje - po uruchomieniu nacisnij Polacz.", "Device is rebooting - tap Connect when it is back."))
            closeDevice()
        }
    }

    /** Odpowiednik "fastboot -w": erase userdata + metadata. System sformatuje je przy starcie. */
    private fun confirmWipe() {
        if (fb == null) { log(tr("Najpierw nacisnij Polacz", "Tap Connect first")); return }
        AlertDialog.Builder(this)
            .setTitle(tr("Wyczysc dane", "Wipe data"))
            .setMessage(tr("Usunac WSZYSTKIE dane z drugiego telefonu (userdata + metadata, jak fastboot -w)?\n\nAplikacje, zdjecia, konta i pamiec wewnetrzna zostana skasowane. Tego nie da sie cofnac.", "Erase ALL data on the second phone (userdata + metadata, like fastboot -w)?\n\nApps, photos, accounts and internal storage will be wiped. This cannot be undone."))
            .setPositiveButton(tr("Wyczysc", "Clear")) { _, _ ->
                withFb { f ->
                    for (p in listOf("metadata", "userdata")) {
                        log("> erase:$p")
                        try { f.command("erase:$p", 10 * 60_000); log(tr("wyczyszczono $p", "wiped $p")) }
                        catch (e: Exception) { log(tr("BLAD erase:$p - ${e.message}", "ERROR erase:$p - ${e.message}")) }
                    }
                    log(tr("Gotowe. Pierwsze uruchomienie systemu potrwa dluzej (formatowanie).", "Done. First boot will take longer (formatting)."))
                }
            }
            .setNegativeButton(tr("Anuluj", "Cancel"), null)
            .show()
    }

    // ---------------- Franken ----------------

    private fun testErofsTools() {
        val t = pl.luki.frankenflasher.franken.erofsTools(this, ::log)
        log("mkfs.erofs: ${t.mkfs.path} (wykonywalny: ${t.mkfs.canExecute()})")
        log("fsck.erofs: ${t.fsck.path} (wykonywalny: ${t.fsck.canExecute()})")
        Thread {
            try {
                log("kod: " + t.run(t.mkfs.path, "--version"))
                log("kod: " + t.run(t.fsck.path, "--version"))
            } catch (e: Exception) { log("BLAD: ${e.message}") }
        }.start()
    }

    private var erofsWriteTest = false
    private lateinit var frBaseView: TextView
    private lateinit var frDonorView: TextView
    private var frBase: File? = null
    private var frDonor: File? = null
    private val frOpt = pl.luki.frankenflasher.franken.Franken.Options()
    private var frResult: FirmwareFolder? = null

    @Suppress("DEPRECATION")
    private fun pickTree(req: Int) {
        if (!hasAllFiles()) { requestAllFiles(); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), req)
    }

    private fun hasAllFiles() = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    private fun requestAllFiles() {
        if (hasAllFiles()) { log(tr("Dostep do wszystkich plikow: jest", "All files access: granted")); return }
        log(tr("Franken potrzebuje dostepu do wszystkich plikow (narzedzia EROFS czytaja obrazy bezposrednio). Wlacz go i wroc.", "Franken needs All files access (EROFS tools read images directly). Enable it and come back."))
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** content://...tree/primary%3ADownload%2Ffw -> /storage/emulated/0/Download/fw */
    private fun treeToFile(u: Uri): File? {
        val id = try { android.provider.DocumentsContract.getTreeDocumentId(u) } catch (_: Exception) { return null }
        val i = id.indexOf(':')
        if (i < 0) return null
        val vol = id.substring(0, i); val path = id.substring(i + 1)
        val root = if (vol.equals("primary", true)) android.os.Environment.getExternalStorageDirectory() else File("/storage/$vol")
        return if (path.isEmpty()) root else File(root, path)
    }

    private fun buildFranken() {
        val b = frBase ?: run { log(tr("Wybierz folder bazy (Neo 8)", "Pick the base folder (Neo 8)")); return }
        val d = frDonor ?: run { log(tr("Wybierz folder dawcy (GT 8 Pro)", "Pick the donor folder (GT 8 Pro)")); return }
        if (!hasAllFiles()) { requestAllFiles(); return }
        val out = File(android.os.Environment.getExternalStorageDirectory(), "FrankenFlasher/franken")
        exec.execute {
            try {
                val need = listOf("my_product", "my_stock", "my_region").sumOf { File(File(d, "SYSTEM"), "$it.img").length() } +
                    File(File(b, "SYSTEM"), "my_product.img").length() * 3
                val free = android.os.Environment.getExternalStorageDirectory().usableSpace
                log(tr("Franken: wolne miejsce ${free / (1 shl 30)} GB, potrzeba ok. ${need / (1 shl 30) + 1} GB", "Franken: free space ${free / (1 shl 30)} GB, need about ${need / (1 shl 30) + 1} GB"))
                if (free < need) throw java.io.IOException(tr("Za malo miejsca", "Not enough storage"))
                out.deleteRecursively()
                val t0 = System.currentTimeMillis()
                val tools = pl.luki.frankenflasher.franken.erofsTools(this, ::log)
                val r = pl.luki.frankenflasher.franken.Franken(b, d, out, File(filesDir, "franken_work"), tools, ::log, ::setStep).build(frOpt)
                val fw = FirmwareFolder(r.groups.mapValues { (_, l) -> l.map { pl.luki.frankenflasher.firmware.DocImage(it) } })
                frResult = fw
                saveFranken(r)
                log(tr("== FRANKEN GOTOWY (${(System.currentTimeMillis() - t0) / 1000} s) ==\n", "== FRANKEN READY (${(System.currentTimeMillis() - t0) / 1000} s) ==\n") + fw.summary())
                setStep(tr("Franken gotowy - podlacz drugi telefon i nacisnij Flashuj Franken", "Franken ready - connect the second phone and tap Flash Franken"))
            } catch (e: Exception) {
                log(tr("BLAD Franken: $e", "Franken ERROR: $e")); e.stackTrace.take(6).forEach { log("    at $it") }
                setStep("BLAD: ${e.message}")
            }
        }
    }

    /** Jak okno Franken w FFF: ktore partycje logiczne z bazy (zaznaczone), reszta z dawcy. */
    private fun choosePartitions() {
        val b = frBase; val d = frDonor
        if (b == null || d == null) { log(tr("Najpierw wybierz baze i dawce", "Pick base and donor first")); return }
        fun names(dir: File) = File(dir, "SYSTEM").listFiles { f -> f.name.endsWith(".img") }.orEmpty().map { it.name.removeSuffix(".img") }.toSet()
        val nb = names(b); val nd = names(d)
        val all = (nb + nd).filter { it != "my_manifest" && it != "my_product" }.sorted()
        val labels = all.map { n -> n + when { n !in nb -> tr("  (tylko dawca)", "  (donor only)"); n !in nd -> tr("  (tylko baza)", "  (base only)"); else -> "" } }.toTypedArray()
        val checked = all.map { it in frOpt.fromBase }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(tr("Zaznaczone = z BAZY, reszta z dawcy", "Checked = from BASE, rest from donor"))
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("OK") { _, _ ->
                frOpt.fromBase = all.filterIndexed { i, _ -> checked[i] }.toMutableSet()
                frResult = null
                saveFranken(null)
                log(tr("Franken: z bazy: ${frOpt.fromBase.sorted().joinToString().ifEmpty { "(nic)" }}; z dawcy: ${all.count { it !in frOpt.fromBase && it in nd } + 2} (z my_product i my_manifest*)", "Franken: from base: ${frOpt.fromBase.sorted().joinToString().ifEmpty { "(none)" }}; from donor: ${all.count { it !in frOpt.fromBase && it in nd } + 2} (incl. my_product and my_manifest*)"))
            }
            .setNegativeButton(tr("Anuluj", "Cancel"), null)
            .show()
    }

    private fun flashFranken() {
        val fw = frResult ?: run { log(tr("Najpierw nacisnij Zbuduj Franken", "Tap Build Franken first")); return }
        flashFolder(fw, "Flash Franken")
    }

    private fun erofsExtractTest(uri: Uri) {
        val write = erofsWriteTest
        Thread {
            try {
                val t = pl.luki.frankenflasher.franken.erofsTools(this, ::log)
                val work = File(getExternalFilesDir(null), "franken_test").apply { deleteRecursively(); mkdirs() }
                val img = File(work, "in.img")
                setStep(tr("Kopiowanie obrazu", "Copying image"))
                contentResolver.openInputStream(uri)!!.use { i -> img.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                if (write) {
                    setStep(tr("Zapis testowy do kopii", "Test write to copy"))
                    val before = img.length()
                    pl.luki.frankenflasher.franken.ErofsPatcher(img).use { p ->
                        val dir = listOf("etc", "").first { it.isEmpty() || p.lookup(it) != null }
                        val path = (if (dir.isEmpty()) "" else "$dir/") + "franken_test.txt"
                        p.putFile(path, "Franken Flasher test\n".toByteArray())
                        log(tr("Dodano /$path, dopisano ${p.commit()} B (obraz ${before / 1024} KB -> ${img.length() / 1024} KB)", "Added /$path, appended ${p.commit()} B (image ${before / 1024} KB -> ${img.length() / 1024} KB)"))
                    }
                    pl.luki.frankenflasher.franken.ErofsPatcher(img).use { p ->
                        val n = p.lookup("etc/franken_test.txt") ?: p.lookup("franken_test.txt")
                        if (n != null) log(tr("Etykieta nowego pliku: ", "New file label: ") + p.xattrs(p.inode(n)).joinToString())
                    }
                    log(tr("fsck.erofs zmienionego obrazu:", "fsck.erofs of modified image:"))
                    log("kod fsck: " + t.run(t.fsck.path, img.path))
                }
                log(tr("Obraz: ${img.length() / (1 shl 20)} MB -> wypakowanie", "Image: ${img.length() / (1 shl 20)} MB -> extracting"))
                setStep(tr("Wypakowanie EROFS", "Extracting EROFS"))
                val t0 = System.currentTimeMillis()
                val out = File(work, "out")
                val rc = t.extract(img, out)
                var files = 0; var bytes = 0L
                out.walkTopDown().forEach { if (it.isFile) { files++; bytes += it.length() } }
                log(tr("kod $rc, plikow: $files, ${bytes / (1 shl 20)} MB, ${(System.currentTimeMillis() - t0) / 1000} s", "code $rc, files: $files, ${bytes / (1 shl 20)} MB, ${(System.currentTimeMillis() - t0) / 1000} s"))
                log("Folder: ${out.path}")
                setStep(tr("Gotowe", "Done"))
            } catch (e: Exception) { log("BLAD: ${e.message}") }
        }.start()
    }

    // ---------------- Flash ----------------

    @Suppress("DEPRECATION")
    private fun pickImage() {
        if (fb == null) { log(tr("Najpierw nacisnij Polacz", "Tap Connect first")); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_IMAGE)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_LOG && resultCode == RESULT_OK) {
            data?.data?.let { u ->
                try {
                    contentResolver.openOutputStream(u)?.use { it.write(logView.text.toString().toByteArray()) }
                    log(tr("Log zapisany", "Log saved"))
                } catch (e: Exception) { log(tr("Nie zapisano logu: ${e.message}", "Log not saved: ${e.message}")) }
            }
            return
        }
        if (requestCode == REQ_FIRMWARE && resultCode == RESULT_OK) { data?.data?.let { loadFirmware(it) }; return }
        if (requestCode == REQ_OTA && resultCode == RESULT_OK) {
            data?.data?.let { u ->
                otaUri = u
                otaName = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "payload.bin"
                updateOtaView()
            }
            return
        }
        if (requestCode == REQ_OTA_OUT && resultCode == RESULT_OK) {
            data?.data?.let { u ->
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                otaOut = u
                updateOtaView()
            }
            return
        }
        if ((requestCode == REQ_FR_BASE || requestCode == REQ_FR_DONOR) && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            val f = treeToFile(u)
            if (f == null || !f.isDirectory) { log(tr("Nie moge zamienic folderu na sciezke - wybierz folder w pamieci wewnetrznej", "Cannot map folder to a path - pick a folder in internal storage")); return }
            if (!File(f, "SYSTEM").isDirectory) log(tr("UWAGA: brak podfolderu SYSTEM w ${f.path}", "WARNING: no SYSTEM subfolder in ${f.path}"))
            if (requestCode == REQ_FR_BASE) { frBase = f; frBaseView.text = "Baza: ${f.path}" } else { frDonor = f; frDonorView.text = "Dawca: ${f.path}" }
            frResult = null
            saveFranken(null)
            return
        }
        if (requestCode == REQ_EROFS_TEST && resultCode == RESULT_OK) {
            data?.data?.let { erofsExtractTest(it) }
            return
        }
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val src = uriSource(uri) ?: run { log(tr("Nie mozna odczytac pliku", "Cannot read file")); return }
        val part = partEdit.text.toString().trim().ifEmpty { src.name.substringBeforeLast('.') }
        AlertDialog.Builder(this)
            .setTitle("Flash")
            .setMessage(tr("Wgrac ${src.name} (${src.size / (1 shl 20)} MB) na partycje \"$part\"?", "Flash ${src.name} (${src.size / (1 shl 20)} MB) to partition \"$part\"?"))
            .setPositiveButton("Flash") { _, _ ->
                withFb { f ->
                    f.flash(part, src, ::setProgress)
                    setProgress(0, 0)
                }
            }
            .setNegativeButton(tr("Anuluj", "Cancel"), null)
            .show()
    }

    private fun uriSource(uri: Uri): ImageSource? {
        var name = "obraz.img"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size <= 0) return null
        val n = name
        val s = size
        return object : ImageSource {
            override val name = n
            override val size = s
            override fun open(): InputStream = contentResolver.openInputStream(uri) ?: throw java.io.IOException("open failed")
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)

private object BuildConfigCompat {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }
}
