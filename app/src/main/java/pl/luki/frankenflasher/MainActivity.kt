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
        private const val REQ_SUPER_OTA = 18
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

    private lateinit var slotChip: TextView
    private lateinit var superEdit: EditText
    private lateinit var stateChip: TextView
    private lateinit var connDot: android.view.View

    private fun setConn(connected: Boolean, name: String? = null) = runOnUiThread {
        status.text = if (connected) (name ?: tr("Polaczono", "Connected")) else tr("Nie polaczono", "Not connected")
        (connDot.background as android.graphics.drawable.GradientDrawable).setColor(if (connected) Ui.OK else Ui.MUTED)
        if (!connected) slotChip.text = tr("Slot -", "Slot -")
    }

    private fun buildUi() {
        val ui = Ui(this)
        val bg = ui.background()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bg.addView(root, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // --- naglowek ---
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14)) }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_franken)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            background = ui.glass(14f, tint = Ui.ACCENT, alphaTop = 30, alphaBottom = 8, stroke = 0).also {
                (it.getDrawable(0) as android.graphics.drawable.GradientDrawable).setStroke(ui.dp(1.5f), Ui.ACCENT)
            }
        }
        head.addView(logo, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        head.addView(TextView(this).apply {
            text = "FRANKEN FLASHER"; textSize = 19f; letterSpacing = 0.04f; setTextColor(Ui.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(ui.dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = BuildConfigCompat.version(this@MainActivity) + "  ·  " + (if (I18n.en) "EN" else "PL")
            textSize = 13f; setTextColor(Ui.TEXT2); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.glass(14f, alphaTop = 20, alphaBottom = 8)
            setOnClickListener {
                I18n.en = !I18n.en
                prefs.edit().putString("lang", if (I18n.en) "en" else "pl").apply()
                recreate()
            }
        })
        root.addView(head)

        // --- pasek stanu: polaczenie | slot | stan ---
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            background = ui.glass(18f); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        fun divider() = android.view.View(this).apply { setBackgroundColor(android.graphics.Color.argb(40, 255, 255, 255)) }
        val c1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        connDot = android.view.View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.MUTED) } }
        c1.addView(connDot, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)))
        status = TextView(this).apply { textSize = 13.5f; setTextColor(Ui.TEXT); setPadding(ui.dp(8), 0, 0, 0); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        c1.addView(status)
        strip.addView(c1, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
        strip.addView(divider(), LinearLayout.LayoutParams(ui.dp(1), ui.dp(22)).apply { marginEnd = ui.dp(12) })
        slotChip = TextView(this).apply { textSize = 13.5f; setTextColor(Ui.TEXT2); isSingleLine = true; text = "Slot -" }
        strip.addView(slotChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
        strip.addView(divider(), LinearLayout.LayoutParams(ui.dp(1), ui.dp(22)).apply { marginEnd = ui.dp(12) })
        stateChip = TextView(this).apply { textSize = 13.5f; setTextColor(Ui.TEXT2); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; text = tr("Gotowy", "Ready") }
        strip.addView(stateChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(4)) })
        setConn(false)

        // --- strony ---
        val pages = List(4) { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(16)) } }
        val pageScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = android.view.View.OVER_SCROLL_NEVER }
        root.addView(pageScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val B = Ui.Kind.PRIMARY; val G = Ui.Kind.GLASS; val D = Ui.Kind.DANGER

        // Strona 1: Fastboot
        val p1 = pages[0]
        p1.addView(ui.title("Fastboot", tr("Drugi telefon przez kabel OTG", "Second phone over OTG cable")))
        val cConn = ui.card(p1, tr("Polaczenie", "Connection"))
        ui.row(cConn, ui.button(tr("Polacz", "Connect"), B, ::connect))
        ui.row(cConn, ui.button("Info", G, ::info), ui.button("getvar all", G, ::getvarAll))
        val cReb = ui.card(p1, tr("Restart", "Reboot"))
        ui.row(cReb, ui.button("fastbootd", G) { simple("reboot-fastboot", true) }, ui.button("bootloader", G) { simple("reboot-bootloader", true) })
        ui.row(cReb, ui.button("system", G) { simple("reboot", true) }, ui.button("recovery", G) { simple("reboot-recovery", true) })
        val cSelf = ui.card(p1, tr("Restart tego telefonu (root)", "Reboot this phone (root)"))
        ui.row(cSelf, ui.button("recovery", G) { selfReboot("recovery") }, ui.button("bootloader", G) { selfReboot("bootloader") })
        ui.row(cSelf, ui.button("fastbootd", G) { selfReboot("fastboot") }, ui.button("system", G) { selfReboot("") })
        val cSlot = ui.card(p1, tr("Aktywny slot", "Active slot"))
        ui.row(cSlot, ui.button("Slot A", G) { simple("set_active:a", false) }, ui.button("Slot B", G) { simple("set_active:b", false) })
        val cImg = ui.card(p1, tr("Pojedynczy obraz", "Single image"))
        cImg.addView(ui.sectionLabel(tr("1. Partycja", "1. Partition")).apply { setPadding(0, 0, 0, ui.dp(4)) })
        partEdit = ui.input(tr("np. boot_a - wybierz z listy albo wpisz", "e.g. boot_a - pick from list or type"))
        cImg.addView(partEdit, ui.lp(bottom = 10))
        ui.row(cImg, ui.button(tr("Lista partycji", "Partition list"), G, ::pickPartition))
        cImg.addView(ui.sectionLabel(tr("2. Plik obrazu", "2. Image file")).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) })
        imgLabel = ui.value("-")
        cImg.addView(imgLabel, ui.lp(bottom = 10))
        ui.row(cImg, ui.button(tr("Wybierz plik", "Pick file"), G, ::pickImage))
        ui.row(cImg, ui.button(tr("3. Flashuj", "3. Flash"), B, ::flashSingle), top = 4)
        val cWipe = ui.card(p1, tr("Dane", "Data"))
        ui.row(cWipe, ui.button(tr("Wyczysc dane (format)", "Wipe data (format)"), D) { confirmWipe() })

        // Strona 2: OTA
        val p2 = pages[1]
        p2.addView(ui.title("OTA", tr("Rozpakowanie payload.bin z sumami SHA-256", "Extract payload.bin with SHA-256 checks")))
        val cOta = ui.card(p2, tr("Zrodlo i cel", "Source and target"))
        otaView = ui.value("OTA: -   " + tr("cel", "target") + ": -")
        cOta.addView(otaView, ui.lp(bottom = 12))
        ui.row(cOta, ui.button(tr("Wybierz OTA", "Pick OTA"), G, ::pickOta), ui.button(tr("Cel", "Target"), G, ::pickOtaOut))
        val cOta2 = ui.card(p2, tr("Akcja", "Action"))
        ui.row(cOta2, ui.button(tr("Rozpakuj", "Extract"), B) { extractOta(false) })
        ui.row(cOta2, ui.button(tr("OTA zip -> hybrid ZIP (super flasher)", "OTA zip -> hybrid ZIP (super flasher)"), G) { extractOta(true) })
        ui.row(cOta2, ui.button(tr("Sprawdz sumy folderu z OTA", "Verify folder checksums against OTA"), G, ::verifyOta))

        // Strona 3: Firmware
        val p3 = pages[2]
        p3.addView(ui.title("Firmware", tr("Folder jak w FFF, wszystko w fastbootd", "FFF-style folder, everything in fastbootd")))
        val cFw = ui.card(p3, tr("Folder", "Folder"))
        fwSummary = ui.body(tr("Nie wybrano folderu", "No folder selected"), 14f, Ui.TEXT)
        fwSummary.typeface = Typeface.MONOSPACE
        cFw.addView(fwSummary, ui.lp(bottom = 12))
        ui.row(cFw, ui.button(tr("Wybierz folder", "Pick folder"), G, ::pickFirmware))
        val cOpt = ui.card(p3, tr("Opcje", "Options"))
        ui.toggle(cOpt, tr("przelacz na drugi slot i wgraj na niego (jak FFF)", "switch to the other slot and flash it (like FFF)"), { opt.switchSlot }, { opt.switchSlot = it })
        ui.toggle(cOpt, tr("od razu usun system starego slotu (szybciej)", "delete old slot system right away (faster)"), { opt.freeOldSlotFirst }, { opt.freeOldSlotFirst = it })
        ui.toggle(cOpt, tr("bez przelaczania: oba sloty (fizyczne a+b, logiczne a)", "no switching: both slots (physical a+b, logical a)"), { opt.bothSlots }, { opt.bothSlots = it })
        ui.toggle(cOpt, tr("vbmeta off (wylacz weryfikacje)", "vbmeta off (disable verification)"), { opt.disableVbmeta }, { opt.disableVbmeta = it })
        ui.toggle(cOpt, "CRITICAL + EXTRA", { opt.flashCritical }, { opt.flashCritical = it; opt.flashExtra = it })
        ui.toggle(cOpt, "MODEM", { opt.flashModem }, { opt.flashModem = it })
        ui.toggle(cOpt, tr("super_empty.img (jesli jest)", "super_empty.img (if present)"), { opt.useSuperEmpty }, { opt.useSuperEmpty = it })
        ui.toggle(cOpt, tr("usun partycje 2. slotu z super", "delete other-slot partitions from super"), { opt.deleteOtherSlot }, { opt.deleteOtherSlot = it })
        ui.toggle(cOpt, tr("restart do systemu na koniec", "reboot to system at the end"), { opt.rebootAtEnd }, { opt.rebootAtEnd = it })
        ui.toggle(cOpt, tr("wyczysc dane (userdata + metadata)", "wipe data (userdata + metadata)"), { opt.wipeData }, { opt.wipeData = it }, danger = true)
        ui.row(p3, ui.button(tr("Flashuj firmware", "Flash firmware"), B, ::flashFirmware))

        val cSup = ui.card(p3, "Super flasher")
        cSup.addView(ui.body(tr("Z rozpakowanego OTA: SYSTEM -> SUPER/super.img + skrypty flash_all.bat/.sh na PC. Rozmiar super z OTA - telefon niepotrzebny.",
            "From an extracted OTA: SYSTEM -> SUPER/super.img + flash_all.bat/.sh scripts for PC. Super size from the OTA - no phone needed.")), ui.lp(bottom = 10))
        superEdit = ui.input(tr("rozmiar super (puste = z OTA, META/super_def.json)", "super size (empty = from OTA, META/super_def.json)"))
        superEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        cSup.addView(superEdit, ui.lp(bottom = 10))
        ui.row(cSup, ui.button(tr("Zbuduj super.img", "Build super.img"), G) { buildSuperFlasher(false) })
        ui.row(cSup, ui.button(tr("Zbuduj hybrid ZIP (recovery + PC)", "Build hybrid ZIP (recovery + PC)"), G) { buildSuperFlasher(true) })
        ui.toggle(cSup, tr("flashuj przez super.img, jesli jest w folderze", "flash via super.img if present in the folder"), { opt.useSuperImage }, { opt.useSuperImage = it })

        // Strona 4: Franken
        val p4 = pages[3]
        p4.addView(ui.title("Franken", tr("Sprzet z bazy, system z dawcy", "Hardware from base, system from donor")))
        val steps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        steps.addView(ui.step(1, tr("Zbuduj", "Build"), true, ::buildFranken), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        steps.addView(ui.step(2, tr("Flashuj", "Flash"), false, ::flashFranken), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
        p4.addView(steps, ui.lp(bottom = 14))
        val cSrc = ui.card(p4, tr("Firmware", "Firmware"))
        cSrc.addView(ui.sectionLabel(tr("Baza (sprzet)", "Base (hardware)")).apply { setPadding(0, 0, 0, ui.dp(2)) })
        frBaseView = ui.value("-")
        cSrc.addView(frBaseView, ui.lp(bottom = 10))
        cSrc.addView(ui.sectionLabel(tr("Dawca (system)", "Donor (system)")).apply { setPadding(0, 0, 0, ui.dp(2)) })
        frDonorView = ui.value("-")
        cSrc.addView(frDonorView, ui.lp(bottom = 12))
        ui.row(cSrc, ui.button(tr("Baza (Neo 8)", "Base (Neo 8)"), G) { pickTree(REQ_FR_BASE) }, ui.button(tr("Dawca (GT 8 Pro)", "Donor (GT 8 Pro)"), G) { pickTree(REQ_FR_DONOR) })
        ui.row(cSrc, ui.button(tr("Partycje: baza / dawca", "Partitions: base / donor"), G, ::choosePartitions))
        ui.row(cSrc, ui.button(tr("Franken -> hybrid ZIP (recovery, bez OTG)", "Franken -> hybrid ZIP (recovery, no OTG)"), B, ::frankenHybrid))
        val cFr = ui.card(p4, tr("Poprawki", "Fixes"))
        ui.toggle(cFr, tr("my_product: konfiguracja sprzetu z bazy", "my_product: hardware config from base"), { frOpt.patchMyProduct }, { frOpt.patchMyProduct = it })
        ui.toggle(cFr, tr("wibracje z bazy (bez qcom_lmvibrator)", "vibration from base (no qcom_lmvibrator)"), { frOpt.fixVibration }, { frOpt.fixVibration = it })
        ui.toggle(cFr, tr("nakladka wyswietlacza z bazy", "display overlay from base"), { frOpt.displayOverlayFromBase }, { frOpt.displayOverlayFromBase = it })
        ui.toggle(cFr, tr("my_manifest: wersja dawcy", "my_manifest: donor version"), { frOpt.patchMyManifest }, { frOpt.patchMyManifest = it })
        ui.toggle(cFr, tr("odm: producent/marka dawcy", "odm: donor manufacturer/brand"), { frOpt.patchOdmIdentity }, { frOpt.patchOdmIdentity = it })
        ui.toggle(cFr, tr("app_v2: odblokuj Telefon/Kontakty/SMS", "app_v2: unlock Phone/Contacts/SMS"), { frOpt.unlockAppV2 }, { frOpt.unlockAppV2 = it })
        ui.toggle(cFr, tr("dzwonki z haptic_ringtone do product", "ringtones from haptic_ringtone to product"), { frOpt.ringtones }, { frOpt.ringtones = it })
        ui.toggle(cFr, tr("biblioteki my_product/lib64 do product (Aparat)", "my_product/lib64 libraries to product (Camera)"), { frOpt.productLibs }, { frOpt.productLibs = it })
        ui.toggle(cFr, tr("pomin my_preload", "skip my_preload"), { frOpt.skipMyPreload }, { frOpt.skipMyPreload = it })
        val cTools = ui.card(p4, tr("Narzedzia / testy", "Tools / tests"))
        ui.row(cTools, ui.button(tr("Dostep do plikow", "File access"), G, ::requestAllFiles), ui.button(tr("Test narzedzi EROFS", "Test EROFS tools"), G, ::testErofsTools))
        ui.row(cTools, ui.button(tr("Test: wypakuj obraz EROFS", "Test: extract EROFS image"), G) {
            erofsWriteTest = false
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_EROFS_TEST)
        })
        ui.row(cTools, ui.button(tr("Test: zapis do kopii obrazu", "Test: write to image copy"), G) {
            erofsWriteTest = true
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_EROFS_TEST)
        })

        // --- panel postepu i logu (szklo) ---
        val act = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ui.glass(20f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) }
        stepView = TextView(this).apply { textSize = 13.5f; setTextColor(Ui.TEXT); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        act.addView(stepView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(60, 255, 255, 255))
        }
        act.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(6) })
        val logBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        var expanded = false
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10.5f; setTextColor(Ui.TEXT2); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }
        logBar.addView(ui.sectionLabel("Log  ⌃").apply {
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            setOnClickListener {
                expanded = !expanded
                scroll.layoutParams = scroll.layoutParams.apply { height = ui.dp(if (expanded) 420 else 120) }
                text = if (expanded) "LOG  ⌄" else "LOG  ⌃"
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun small(t: String, a: () -> Unit) = TextView(this).apply {
            text = t; textSize = 12.5f; setTextColor(Ui.ACCENT); setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); setOnClickListener { a() }
        }
        logBar.addView(small(tr("Zapisz", "Save")) { saveLog() })
        logBar.addView(small(tr("Udostepnij", "Share")) { shareLog() })
        logBar.addView(small(tr("Wyczysc", "Clear")) { logView.text = "" })
        act.addView(logBar)
        act.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(120)))
        root.addView(act, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) })

        // --- dolna nawigacja ---
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = ui.glass(26f, alphaTop = 30, alphaBottom = 14); setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        val items = listOf(
            ui.navItem(R.drawable.ic_nav_fastboot, "Fastboot"), ui.navItem(R.drawable.ic_nav_ota, "OTA"),
            ui.navItem(R.drawable.ic_nav_firmware, "Firmware"), ui.navItem(R.drawable.ic_nav_franken, "Franken"),
        )
        fun showTab(i: Int) {
            pageScroll.removeAllViews()
            pageScroll.addView(pages[i])
            pageScroll.scrollTo(0, 0)
            items.forEachIndexed { k, it -> ui.setNavSelected(it, k == i) }
            prefs.edit().putInt("tab", i).apply()
        }
        items.forEachIndexed { i, it ->
            it.view.setOnClickListener { showTab(i) }
            nav.addView(it.view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = ui.dp(4) })
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(10)) })

        showTab(prefs.getInt("tab", 0).coerceIn(0, 3))
        restoreState()
        // Android 15+ rysuje pod paskami systemowymi - odsun tresc o ich wysokosc
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContentView(bg)
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
            setConn(true, dev.productName ?: dev.deviceName)
            if (showInfo) info()
        } catch (e: Exception) {
            log(tr("Blad polaczenia: ${e.message}", "Connection error: ${e.message}"))
        }
    }

    private fun closeDevice() {
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        fb = null
        setConn(false)
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

    private fun setStep(s: String) = runOnUiThread { stepView.text = s; stateChip.text = s.substringBefore(':').take(24) }

    @Suppress("DEPRECATION")
    private fun pickFirmware() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FIRMWARE)
    }

    private val prefs by lazy { getSharedPreferences("ff", MODE_PRIVATE) }

    /** Przywraca foldery i ostatni zbudowany Franken z poprzedniego uruchomienia. */
    private fun restoreState() {
        val perms = contentResolver.persistedUriPermissions.map { it.uri }
        prefs.getString("otaUri", null)?.let { Uri.parse(it) }?.takeIf { it in perms }?.let { otaUri = it; otaName = prefs.getString("otaName", "") ?: "" }
        prefs.getString("otaOut", null)?.let { Uri.parse(it) }?.takeIf { it in perms }?.let { otaOut = it }
        if (otaUri != null || otaOut != null) updateOtaView()
        prefs.getString("fwTree", null)?.let { u ->
            val uri = Uri.parse(u)
            if (contentResolver.persistedUriPermissions.any { it.uri == uri }) loadFirmware(uri, save = false)
        }
        prefs.getString("frBase", null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { frBase = it; frBaseView.text = it.path.removePrefix("/storage/emulated/0/") }
        prefs.getString("frDonor", null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { frDonor = it; frDonorView.text = it.path.removePrefix("/storage/emulated/0/") }
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
        if (save) { prefs.edit().putString("fwTree", tree.toString()).apply(); superFolder = null }
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

    private fun docIdToFile(id: String): File? {
        val i = id.indexOf(':'); if (i < 0) return null
        val vol = id.substring(0, i); val path = id.substring(i + 1)
        val root = if (vol.equals("primary", true)) android.os.Environment.getExternalStorageDirectory() else File("/storage/$vol")
        return if (path.isEmpty()) root else File(root, path)
    }

    private fun isExtracted(d: File?) = d != null && File(d, "SYSTEM").isDirectory &&
        (File(d, "extract_ok.txt").isFile || File(d, "super_groups.txt").isFile || File(d, "super_def.json").isFile)

    /** Wczesniej rozpakowany folder tego OTA: zapamietany po rozpakowaniu albo w folderze docelowym (takze "nazwa (1)" itd.). */
    private fun findExtracted(): File? {
        val base = otaName.substringBeforeLast('.').ifEmpty { "firmware" }
        val c = ArrayList<File?>()
        if (otaName.isEmpty() || prefs.getString("lastExtractOta", null) == otaName) c += prefs.getString("lastExtract", null)?.let { File(it) }
        treeToFile(otaOut ?: Uri.EMPTY)?.let { o ->
            if (otaName.isNotEmpty()) { c += File(o, base); (1..5).forEach { c += File(o, "$base ($it)") } }
            c += o
        }
        return c.firstOrNull { isExtracted(it) }
    }

    private fun extractOta(thenHybrid: Boolean = false) {
        if (busy) { log(tr("Trwa inna operacja - poczekaj az sie skonczy", "Another operation is running - wait until it finishes")); return }
        // juz rozpakowane wczesniej - od razu buduj hybrid ZIP (bez ponownego rozpakowania)
        if (thenHybrid) findExtracted()?.let { prev ->
            log(tr("OTA juz rozpakowane: ${prev.path.removePrefix("/storage/emulated/0/")} - pomijam rozpakowanie, buduje hybrid ZIP",
                "OTA already extracted: ${prev.path.removePrefix("/storage/emulated/0/")} - skipping extraction, building hybrid ZIP"))
            superFolder = prev
            buildSuperFlasher(true)
            return
        }
        val inUri = otaUri ?: run { log(tr("Najpierw wybierz plik OTA", "Pick an OTA file first")); return }
        val out = otaOut ?: run { log(tr("Najpierw wybierz folder docelowy", "Pick a target folder first")); return }
        busy = true
        exec.execute {
            var dir: File? = null
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
                docIdToFile(r.folderDocId)?.takeIf { it.isDirectory }?.let { prefs.edit().putString("lastExtract", it.path).putString("lastExtractOta", otaName).apply() }
                if (thenHybrid) {
                    dir = docIdToFile(r.folderDocId)
                    if (dir == null || !dir.isDirectory) { dir = null; log(tr("Nie moge znalezc rozpakowanego folderu jako sciezki - wybierz go w zakladce Firmware", "Cannot map the extracted folder to a path - pick it in the Firmware tab")) }
                }
            } catch (e: Exception) {
                log(tr("BLAD rozpakowania: ${e.message ?: e}", "EXTRACTION ERROR: ${e.message ?: e}"))
                setStep(tr("BLAD rozpakowania", "EXTRACTION ERROR"))
            } finally { setProgress(0, 0); busy = false }
            dir?.let { d -> superFolder = d; runOnUiThread { buildSuperFlasher(true) } }
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
        for (v in listOf("product", "current-slot", "is-userspace", "unlocked", "secure", "max-download-size", "version-bootloader", "partition-size:super")) {
            val r = try { f.getVar(v) } catch (e: Exception) { "-" }
            if (v == "current-slot") runOnUiThread { slotChip.text = "Slot " + r.trim().removePrefix("_").uppercase() }
            if (v == "partition-size:super") r.trim().let { t ->
                (if (t.startsWith("0x", true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull())?.takeIf { it > 0 }?.let { sz ->
                    prefs.edit().putLong("superSize", sz).apply()
                }
            }
            log("  $v: $r")
        }
    }

    private fun getvarAll() = withFb { f -> f.getVarAll().forEach { log("  $it") } }

    /** Restart telefonu, na ktorym dziala aplikacja - wymaga roota (Magisk/KernelSU/APatch). */
    private fun selfReboot(mode: String) {
        val label = mode.ifEmpty { "system" }
        AlertDialog.Builder(this)
            .setTitle(tr("Restart tego telefonu", "Reboot this phone"))
            .setMessage(tr("Uruchomic ten telefon ponownie do: $label?", "Reboot this phone to: $label?"))
            .setPositiveButton(tr("Restart", "Reboot")) { _, _ ->
                Thread {
                    try {
                        val p = ProcessBuilder("su", "-c", if (mode.isEmpty()) "reboot" else "reboot $mode").redirectErrorStream(true).start()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        val rc = p.waitFor()
                        if (rc != 0) log(tr("Restart nieudany (kod $rc): $out - brak roota albo odmowa w menedzerze roota", "Reboot failed (code $rc): $out - no root or denied in the root manager"))
                    } catch (e: Exception) {
                        log(tr("Brak roota (su): ${e.message} - restart tego telefonu wymaga Magisk/KernelSU/APatch", "No root (su): ${e.message} - rebooting this phone needs Magisk/KernelSU/APatch"))
                    }
                }.start()
            }
            .setNegativeButton(tr("Anuluj", "Cancel"), null)
            .show()
    }

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

    private var superHybrid = false
    private var superFolder: File? = null

    private fun buildSuperFlasher(hybrid: Boolean = superHybrid) {
        if (busy) { log(tr("Trwa inna operacja - poczekaj az sie skonczy", "Another operation is running - wait until it finishes")); return }
        superHybrid = hybrid
        val u = prefs.getString("fwTree", null)?.let { Uri.parse(it) }
        val folder = superFolder ?: u?.let { treeToFile(it) }
        if (folder == null || !File(folder, "SYSTEM").isDirectory) { log(tr("Najpierw wybierz folder firmware z podfolderem SYSTEM (rozpakowane OTA) albo uzyj 'OTA zip -> hybrid ZIP' w zakladce OTA",
            "Pick a firmware folder with a SYSTEM subfolder (extracted OTA) or use 'OTA zip -> hybrid ZIP' in the OTA tab")); return }
        if (!hasAllFiles()) { requestAllFiles(); return }
        busy = true
        // rozmiar super: wpisany > z OTA (super_def.json) > zapamietany z telefonu (Polacz) > wyliczony z grupy w payload
        val size = superEdit.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
            ?: prefs.getLong("superSize", 0L).takeIf { it > 0 && !File(folder, "super_def.json").isFile }?.also {
                log(tr("super: rozmiar zapamietany z telefonu: $it B", "super: size remembered from the phone: $it B"))
            }
        exec.execute {
            busy = true
            try {
                // rozmiar super z OTA: META/super_def.json (zapisany przy rozpakowaniu albo z wybranego ZIP)
                val defFile = File(folder, "super_def.json")
                if (!defFile.isFile) otaUri?.takeIf { otaName.endsWith(".zip", true) }?.let { zu ->
                    contentResolver.openFileDescriptor(zu, "r")?.use { pfd ->
                        val ch = java.io.FileInputStream(pfd.fileDescriptor).channel
                        pl.luki.frankenflasher.ota.ZipLocator.readEntry(ch) { n -> n.contains("META/") && n.substringAfterLast('/').startsWith("super_def") && n.endsWith(".json") }
                            ?.let { (n, b) -> defFile.writeBytes(b); log(tr("super_def.json z $otaName ($n)", "super_def.json from $otaName ($n)")) }
                    }
                }
                if (!defFile.isFile && size == null && File(folder, "super_groups.txt").isFile) {
                    log(tr("super: brak super_def.json - rozmiar wyliczony z grupy w payload (grupa + 4 MB, jak w konfiguracjach Qualcomm Virtual A/B)",
                        "super: no super_def.json - size derived from the payload group (group + 4 MB, as in Qualcomm Virtual A/B configs)"))
                } else if (!defFile.isFile && size == null) {
                    log(tr("Brak super_def.json w folderze - wybierz plik OTA zip tego firmware (wystarczy odczyt, bez rozpakowania)",
                        "No super_def.json in the folder - pick the OTA zip of this firmware (read only, no extraction)"))
                    runOnUiThread {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_SUPER_OTA)
                    }
                    return@execute
                }
                val t0 = System.currentTimeMillis()
                if (hybrid) {
                    setStep(tr("Budowanie hybrid ZIP", "Building hybrid ZIP"))
                    val zip = File(folder.parentFile, folder.name + "_hybrid.zip")
                    pl.luki.frankenflasher.franken.HybridZip(::log, ::setProgress).build(folder, size, opt.disableVbmeta, zip)
                    log(tr("Recovery (OrangeFox/AERA/TWRP): Install -> ${zip.name}. PC: rozpakuj ZIP i uruchom flash_all.bat w bootloaderze.",
                        "Recovery (OrangeFox/AERA/TWRP): Install -> ${zip.name}. PC: extract the ZIP and run flash_all.bat in bootloader."))
                } else {
                    setStep(tr("Budowanie super.img", "Building super.img"))
                    pl.luki.frankenflasher.franken.SuperFlasher(::log, ::setProgress).build(folder, size)
                }
                log(tr("Super flasher gotowy w ${(System.currentTimeMillis() - t0) / 1000} s: ${folder.path}", "Super flasher ready in ${(System.currentTimeMillis() - t0) / 1000} s: ${folder.path}"))
                setStep(tr("Super flasher gotowy", "Super flasher ready"))
                if (superFolder == null) u?.let { loadFirmware(it, save = false) }
            } catch (e: Exception) {
                log(tr("BLAD super: $e", "super ERROR: $e")); setStep(tr("BLAD super", "super ERROR"))
            } finally { busy = false; setProgress(0, 0) }
        }
    }

    /** Zbudowany Franken jako hybrid ZIP: firmware bazy + SYSTEM z wyniku, rozmiar super z folderu bazy. */
    private fun frankenHybrid() {
        if (busy) { log(tr("Trwa inna operacja - poczekaj az sie skonczy", "Another operation is running - wait until it finishes")); return }
        val txt = prefs.getString("frResult", null)
        if (frResult == null || txt == null) { log(tr("Najpierw nacisnij Zbuduj Franken", "Tap Build Franken first")); return }
        val groups = LinkedHashMap<String, MutableList<File>>()
        txt.lines().filter { it.contains('|') }.forEach { l -> val (g, p) = l.split('|', limit = 2); groups.getOrPut(g) { ArrayList() } += File(p) }
        groups.values.flatten().firstOrNull { !it.isFile }?.let { log(tr("Brak pliku ${it.path} - zbuduj Franken ponownie", "Missing ${it.path} - build Franken again")); return }
        val base = frBase ?: groups["SYSTEM"]?.firstOrNull { it.parentFile?.parentFile?.let { d -> File(d, "CRITICAL").isDirectory } == true }?.parentFile?.parentFile
        if (base == null) { log(tr("Wybierz folder bazy (Neo 8)", "Pick the base folder (Neo 8)")); return }
        if (!hasAllFiles()) { requestAllFiles(); return }
        val size = superEdit.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
            ?: prefs.getLong("superSize", 0L).takeIf { it > 0 && !File(base, "super_def.json").isFile && !File(base, "super_groups.txt").isFile }?.also {
                log(tr("super: rozmiar zapamietany z telefonu: $it B", "super: size remembered from the phone: $it B"))
            }
        if (size == null && !File(base, "super_def.json").isFile && !File(base, "super_groups.txt").isFile) {
            log(tr("Folder bazy nie ma super_groups.txt ani super_def.json - rozpakuj baze w tej aplikacji (zakladka OTA), wpisz rozmiar super (Neo 8: 18790481920) albo raz podlacz telefon (Polacz)",
                "Base folder has no super_groups.txt or super_def.json - extract the base in this app (OTA tab), enter the super size (Neo 8: 18790481920) or connect the phone once (Connect)"))
            return
        }
        busy = true
        exec.execute {
            try {
                setStep(tr("Budowanie Franken hybrid ZIP", "Building Franken hybrid ZIP"))
                val t0 = System.currentTimeMillis()
                val zip = File(android.os.Environment.getExternalStorageDirectory(), "FrankenFlasher/Franken_hybrid.zip")
                pl.luki.frankenflasher.franken.HybridZip(::log, ::setProgress).build(groups, base, size, opt.disableVbmeta, zip)
                log(tr("Recovery (TWRP/AERA/OrangeFox): Install -> FrankenFlasher/${zip.name}, potem Format Data (zmiana systemu). Gotowe w ${(System.currentTimeMillis() - t0) / 1000} s",
                    "Recovery (TWRP/AERA/OrangeFox): Install -> FrankenFlasher/${zip.name}, then Format Data (system change). Done in ${(System.currentTimeMillis() - t0) / 1000} s"))
                setStep(tr("Franken hybrid ZIP gotowy", "Franken hybrid ZIP ready"))
            } catch (e: Exception) {
                log(tr("BLAD Franken hybrid: $e", "Franken hybrid ERROR: $e")); setStep(tr("BLAD Franken hybrid", "Franken hybrid ERROR"))
            } finally { busy = false; setProgress(0, 0) }
        }
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
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                prefs.edit().putString("otaUri", u.toString()).putString("otaName", otaName).apply()
                updateOtaView()
            }
            return
        }
        if (requestCode == REQ_OTA_OUT && resultCode == RESULT_OK) {
            data?.data?.let { u ->
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                otaOut = u
                prefs.edit().putString("otaOut", u.toString()).apply()
                updateOtaView()
            }
            return
        }
        if ((requestCode == REQ_FR_BASE || requestCode == REQ_FR_DONOR) && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            val f = treeToFile(u)
            if (f == null || !f.isDirectory) { log(tr("Nie moge zamienic folderu na sciezke - wybierz folder w pamieci wewnetrznej", "Cannot map folder to a path - pick a folder in internal storage")); return }
            if (!File(f, "SYSTEM").isDirectory) log(tr("UWAGA: brak podfolderu SYSTEM w ${f.path}", "WARNING: no SYSTEM subfolder in ${f.path}"))
            if (requestCode == REQ_FR_BASE) { frBase = f; frBaseView.text = f.path.removePrefix("/storage/emulated/0/") } else { frDonor = f; frDonorView.text = f.path.removePrefix("/storage/emulated/0/") }
            frResult = null
            saveFranken(null)
            return
        }
        if (requestCode == REQ_SUPER_OTA && resultCode == RESULT_OK) {
            val zu = data?.data ?: return
            val folder = superFolder ?: prefs.getString("fwTree", null)?.let { treeToFile(Uri.parse(it)) } ?: return
            exec.execute {
                try {
                    val found = contentResolver.openFileDescriptor(zu, "r")?.use { pfd ->
                        val ch = java.io.FileInputStream(pfd.fileDescriptor).channel
                        pl.luki.frankenflasher.ota.ZipLocator.readEntry(ch) { n -> n.contains("META/") && n.substringAfterLast('/').startsWith("super_def") && n.endsWith(".json") }
                    }
                    if (found != null) {
                        File(folder, "super_def.json").writeBytes(found.second)
                        log(tr("Zapisano super_def.json z OTA (${found.first})", "Saved super_def.json from OTA (${found.first})"))
                    } else {
                        // brak META/super_def.json (OTA OPPO/realme) - grupy partycji z naglowka payload.bin
                        val groups = contentResolver.openFileDescriptor(zu, "r")?.use { pfd ->
                            val ch = java.io.FileInputStream(pfd.fileDescriptor).channel
                            val base = pl.luki.frankenflasher.ota.ZipLocator.findStored(ch, "payload.bin") ?: 0L
                            pl.luki.frankenflasher.ota.PayloadParser.parse(ch, base).groups
                        }.orEmpty()
                        if (groups.isEmpty()) { log(tr("W tym pliku nie ma ukladu super (ani META/super_def.json, ani grup w payload)", "No super layout in this file (no META/super_def.json, no payload groups)")); return@execute }
                        File(folder, "super_groups.txt").writeText(groups.joinToString("\n") { g -> "${g.name} ${g.size} ${g.partitions.joinToString(",")}" } + "\n")
                        log(tr("Uklad super z payload.bin: ${groups.joinToString { "${it.name} ${it.size / (1 shl 20)} MB" }}", "Super layout from payload.bin: ${groups.joinToString { "${it.name} ${it.size / (1 shl 20)} MB" }}"))
                    }
                    runOnUiThread { buildSuperFlasher(superHybrid) }
                } catch (e: Exception) { log("super_def: $e") }
            }
            return
        }
        if (requestCode == REQ_EROFS_TEST && resultCode == RESULT_OK) {
            data?.data?.let { erofsExtractTest(it) }
            return
        }
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val src = uriSource(uri) ?: run { log(tr("Nie mozna odczytac pliku", "Cannot read file")); return }
        imgUri = uri
        imgLabel.text = "${src.name}  (${src.size / (1 shl 20)} MB)"
        if (partEdit.text.isBlank()) log(tr("Plik: ${src.name} - teraz wybierz partycje", "File: ${src.name} - now pick the partition"))
    }

    private var imgUri: Uri? = null
    private lateinit var imgLabel: TextView

    /** Lista partycji z telefonu (getvar all: partition-size:*), jak w FFF. Bez polaczenia - typowe nazwy. */
    private fun pickPartition() {
        val common = listOf("boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta", "vbmeta_system", "vbmeta_vendor", "modem", "abl", "xbl", "super")
        fun show(items: List<String>, fromDevice: Boolean) = runOnUiThread {
            val arr = items.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(if (fromDevice) tr("Partycje w telefonie (${arr.size})", "Partitions on phone (${arr.size})") else tr("Typowe partycje (polacz telefon, by zobaczyc wszystkie)", "Common partitions (connect phone to see all)"))
                .setItems(arr) { _, i -> partEdit.setText(arr[i].substringBefore("  ")) }
                .setNegativeButton(tr("Anuluj", "Cancel"), null)
                .show()
        }
        val f = fb ?: run { show(common, false); return }
        exec.execute {
            busy = true
            try {
                val sizes = LinkedHashMap<String, Long>()
                val logical = HashSet<String>()
                for (l in f.getVarAll()) {
                    Regex("""partition-size:([^:]+):\s*(0x[0-9a-fA-F]+|\d+)""").find(l)?.let { m ->
                        val v = m.groupValues[2]
                        sizes[m.groupValues[1]] = if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
                    }
                    Regex("""is-logical:([^:]+):\s*yes""").find(l)?.let { logical += it.groupValues[1] }
                }
                if (sizes.isEmpty()) show(common, false)
                else show(sizes.keys.sorted().map { n ->
                    val sz = sizes.getValue(n)
                    n + "  " + (if (sz >= (1 shl 20)) "${sz / (1 shl 20)} MB" else "${sz / 1024} KB") + (if (n in logical) tr("  (logiczna)", "  (logical)") else "")
                }, true)
            } catch (e: Exception) { log("getvar all: ${e.message}"); show(common, false) }
            finally { busy = false }
        }
    }

    private fun flashSingle() {
        val uri = imgUri ?: run { log(tr("Wybierz plik obrazu", "Pick an image file")); return }
        val part = partEdit.text.toString().trim()
        if (part.isEmpty()) { log(tr("Wybierz partycje z listy albo wpisz nazwe", "Pick a partition from the list or type its name")); return }
        if (fb == null) { log(tr("Najpierw nacisnij Polacz", "Tap Connect first")); return }
        val src = uriSource(uri) ?: run { log(tr("Nie mozna odczytac pliku", "Cannot read file")); return }
        AlertDialog.Builder(this)
            .setTitle("Flash")
            .setMessage(tr("Wgrac ${src.name} (${src.size / (1 shl 20)} MB) na partycje \"$part\"?", "Flash ${src.name} (${src.size / (1 shl 20)} MB) to partition \"$part\"?"))
            .setPositiveButton("Flash") { _, _ ->
                withFb { f ->
                    setStep("$part <- ${src.name}")
                    f.flash(part, src, ::setProgress)
                    setProgress(0, 0)
                    setStep(tr("Gotowe: $part", "Done: $part"))
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
