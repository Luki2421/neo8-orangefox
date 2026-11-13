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
                    else log("Brak zgody na dostep USB")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev != null && dev.deviceName == transport?.device?.deviceName && !busy) {
                        log("Urzadzenie odlaczone")
                        closeDevice()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsbFastbootTransport.onChunkDowngrade = { log("USB: kontroler odrzucil duza paczke, przechodze na ${it / 1024} KB") }
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
        log("Franken Flasher ${BuildConfigCompat.version(this)} - etap 2: fastboot + firmware + OTA")
        log("Podlacz drugi telefon w trybie fastboot (lub fastbootd) i nacisnij Polacz.")
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
        status = TextView(this).apply { text = "Nie polaczono  (v" + BuildConfigCompat.version(this@MainActivity) + ")"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
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
        row(p1, "Polacz" to ::connect, "Info" to ::info, "getvar all" to ::getvarAll)
        row(p1, "-> fastbootd" to { simple("reboot-fastboot", true) },
            "-> bootloader" to { simple("reboot-bootloader", true) },
            "-> system" to { simple("reboot", true) })
        row(p1, "Slot A" to { simple("set_active:a", false) }, "Slot B" to { simple("set_active:b", false) })
        row(p1, "Wyczysc dane (format)" to { confirmWipe() })
        header(p1, "Pojedynczy obraz")
        partEdit = EditText(this).apply { hint = "partycja (puste = z nazwy pliku, np. boot_a)"; isSingleLine = true }
        p1.addView(partEdit)
        row(p1, "Wybierz obraz i flashuj" to ::pickImage)

        // Strona 2: OTA
        val p2 = pages[1]
        header(p2, "Rozpakuj OTA (zip / payload.bin)")
        otaView = TextView(this).apply { text = "OTA: -   cel: -"; textSize = 13f }
        p2.addView(otaView)
        row(p2, "Wybierz OTA" to ::pickOta, "Cel" to ::pickOtaOut)
        row(p2, "Rozpakuj" to ::extractOta)
        row(p2, "Sprawdz sumy folderu z OTA" to ::verifyOta)

        // Strona 3: Firmware
        val p3 = pages[2]
        header(p3, "Firmware (folder jak w FFF)")
        fwSummary = TextView(this).apply { text = "Nie wybrano folderu"; textSize = 13f }
        p3.addView(fwSummary)
        row(p3, "Wybierz folder" to ::pickFirmware)
        p3.addView(chk("przelacz na drugi slot i wgraj na niego (jak FFF)", { opt.switchSlot }, { opt.switchSlot = it }))
        p3.addView(chk("bez przelaczania: oba sloty (fizyczne a+b, logiczne a)", { opt.bothSlots }, { opt.bothSlots = it }))
        p3.addView(chk("vbmeta off (wylacz weryfikacje)", { opt.disableVbmeta }, { opt.disableVbmeta = it }))
        p3.addView(chk("CRITICAL + EXTRA", { opt.flashCritical }, { opt.flashCritical = it; opt.flashExtra = it }))
        p3.addView(chk("MODEM", { opt.flashModem }, { opt.flashModem = it }))
        p3.addView(chk("super_empty.img (jesli jest)", { opt.useSuperEmpty }, { opt.useSuperEmpty = it }))
        p3.addView(chk("usun partycje 2. slotu z super", { opt.deleteOtherSlot }, { opt.deleteOtherSlot = it }))
        p3.addView(chk("restart do systemu na koniec", { opt.rebootAtEnd }, { opt.rebootAtEnd = it }))
        p3.addView(chk("wyczysc dane (userdata + metadata)", { opt.wipeData }, { opt.wipeData = it }).apply {
            setTextColor(android.graphics.Color.rgb(255, 120, 110))
        })
        row(p3, "Flashuj firmware" to ::flashFirmware)

        val p4 = pages[3]
        header(p4, "Franken (etap 3 - w budowie)")
        row(p4, "Test narzedzi EROFS" to ::testErofsTools)
        row(p4, "Test: wypakuj obraz EROFS" to {
            erofsWriteTest = false
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_EROFS_TEST)
        })
        row(p4, "Test: zapis do kopii obrazu" to {
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
        logBar.addView(Button(this).apply { text = "Zapisz"; isAllCaps = false; textSize = 12f; setOnClickListener { saveLog() } })
        logBar.addView(Button(this).apply { text = "Udostepnij"; isAllCaps = false; textSize = 12f; setOnClickListener { shareLog() } })
        logBar.addView(Button(this).apply { text = "Wyczysc"; isAllCaps = false; textSize = 12f; setOnClickListener { logView.text = "" } })
        root.addView(logBar)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView); setBackgroundColor(android.graphics.Color.rgb(24, 24, 24)) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        showTab(0)
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
            .putExtra(Intent.EXTRA_SUBJECT, "Franken Flasher log").putExtra(Intent.EXTRA_TEXT, text), "Udostepnij log"))
    }

    private fun setProgress(done: Long, total: Long) = runOnUiThread {
        progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
    }

    // ---------------- USB ----------------

    private fun connect() {
        if (fb != null) { log("Juz polaczono: ${transport?.device?.productName}"); return }
        val dev = usb.deviceList.values.firstOrNull { UsbFastbootTransport.findInterface(it) != null }
        if (dev == null) { log("Nie znaleziono urzadzenia fastboot. Sprawdz kabel OTG i tryb fastboot."); return }
        requestOrOpen(dev)
    }

    private fun requestOrOpen(dev: UsbDevice) {
        if (usb.hasPermission(dev)) { openDevice(dev); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
        usb.requestPermission(dev, pi)
        log("Prosba o dostep do USB...")
    }

    private fun openDevice(dev: UsbDevice, showInfo: Boolean = true) {
        closeDevice()
        try {
            val t = UsbFastbootTransport.open(usb, dev)
            transport = t
            fb = Fastboot(t, ::log)
            log("Polaczono: ${dev.manufacturerName ?: ""} ${dev.productName ?: ""} (${dev.deviceName})")
            runOnUiThread { status.text = "Polaczono: ${dev.productName ?: dev.deviceName}  (v" + BuildConfigCompat.version(this) + ")" }
            if (showInfo) info()
        } catch (e: Exception) {
            log("Blad polaczenia: ${e.message}")
        }
    }

    private fun closeDevice() {
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        fb = null
        runOnUiThread { status.text = "Nie polaczono  (v" + BuildConfigCompat.version(this) + ")" }
    }

    private fun withFb(block: (Fastboot) -> Unit) {
        val f = fb ?: run { log("Najpierw nacisnij Polacz"); return }
        exec.execute {
            busy = true
            try { block(f) } catch (e: Exception) { log("BLAD: ${e.message}") } finally { busy = false }
        }
    }

    /** Czeka, az urzadzenie wroci po restarcie (fastboot/fastbootd) i laczy sie ponownie. Wywolywac z watku roboczego. */
    private fun reconnectBlocking(timeoutMs: Long): Fastboot {
        closeDevice()
        log("Czekam na ponowne polaczenie...")
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
        throw FastbootException("Urzadzenie nie wrocilo po restarcie")
    }

    private fun setStep(s: String) = runOnUiThread { stepView.text = s }

    @Suppress("DEPRECATION")
    private fun pickFirmware() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FIRMWARE)
    }

    private fun loadFirmware(tree: Uri) {
        try { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        exec.execute {
            try {
                val fw = FirmwareFolder.scan(this, tree)
                firmware = fw
                runOnUiThread { fwSummary.text = fw.summary() }
                log("Folder firmware:\n" + fw.summary())
                if (fw.groups.isEmpty()) log("Brak podfolderow BOOTLOADER/CRITICAL/SYSTEM... - wybierz glowny folder firmware")
            } catch (e: Exception) { log("Blad odczytu folderu: ${e.message}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickOta() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_OTA)
    }

    @Suppress("DEPRECATION")
    private fun pickOtaOut() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OTA_OUT) }

    private fun updateOtaView() = runOnUiThread {
        otaView.text = "OTA: ${otaName.ifEmpty { "-" }}\ncel: ${otaOut?.lastPathSegment?.substringAfterLast(':') ?: "-"}"
    }

    private fun extractOta() {
        val inUri = otaUri ?: run { log("Najpierw wybierz plik OTA"); return }
        val out = otaOut ?: run { log("Najpierw wybierz folder docelowy"); return }
        exec.execute {
            try {
                setStep("Rozpakowywanie $otaName...")
                log("Rozpakowywanie $otaName")
                val t0 = System.currentTimeMillis()
                val r = OtaExtractor(this, ::log, ::setProgress).extract(inUri, otaName, out)
                val fw = FirmwareFolder.scan(this, r.tree, r.folderDocId)
                firmware = fw
                runOnUiThread { fwSummary.text = fw.summary() }
                log("Gotowe w ${(System.currentTimeMillis() - t0) / 1000} s. Folder ustawiony do flashowania:\n" + fw.summary())
                setStep("Rozpakowano - mozna flashowac")
            } catch (e: Exception) {
                log("BLAD rozpakowania: ${e.message ?: e}")
                setStep("BLAD rozpakowania")
            } finally { setProgress(0, 0) }
        }
    }

    private fun verifyOta() {
        val inUri = otaUri ?: run { log("Najpierw wybierz plik OTA"); return }
        val fw = firmware ?: run { log("Najpierw wybierz folder firmware (sekcja Firmware)"); return }
        exec.execute {
            try {
                setStep("Sprawdzanie sum...")
                val images = fw.groups.values.flatten().associate { it.base to it.uri }
                val bad = OtaExtractor(this, ::log, ::setProgress).verify(inUri, otaName, images)
                if (bad.isEmpty()) { log("Wszystkie sumy SHA-256 zgodne"); setStep("Sumy OK - mozna flashowac") }
                else { bad.forEach { log("BLAD: $it") }; setStep("BLEDNE SUMY (${bad.size}) - nie flashuj") }
            } catch (e: Exception) {
                log("BLAD sprawdzania: ${e.message ?: e}"); setStep("BLAD sprawdzania")
            } finally { setProgress(0, 0) }
        }
    }

    private fun flashFirmware() {
        val fw = firmware ?: run { log("Najpierw wybierz folder firmware"); return }
        if (fb == null) { log("Najpierw nacisnij Polacz"); return }
        AlertDialog.Builder(this)
            .setTitle("Flash firmware")
            .setMessage(fw.summary() + "\n\nWgrac na podlaczony telefon (" + (if (opt.switchSlot) "przelaczenie na drugi slot" else if (opt.bothSlots) "oba sloty" else "aktualny slot") + ")? Wszystko w fastbootd. Nie odlaczaj kabla do konca." +
                (if (opt.wipeData) "\n\nUWAGA: WSZYSTKIE DANE na telefonie (zdjecia, aplikacje, konta) zostana USUNIETE." else ""))
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
            .setNegativeButton("Anuluj", null)
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
            log("Urzadzenie sie restartuje - po uruchomieniu nacisnij Polacz.")
            closeDevice()
        }
    }

    /** Odpowiednik "fastboot -w": erase userdata + metadata. System sformatuje je przy starcie. */
    private fun confirmWipe() {
        if (fb == null) { log("Najpierw nacisnij Polacz"); return }
        AlertDialog.Builder(this)
            .setTitle("Wyczysc dane")
            .setMessage("Usunac WSZYSTKIE dane z drugiego telefonu (userdata + metadata, jak fastboot -w)?\n\nAplikacje, zdjecia, konta i pamiec wewnetrzna zostana skasowane. Tego nie da sie cofnac.")
            .setPositiveButton("Wyczysc") { _, _ ->
                withFb { f ->
                    for (p in listOf("metadata", "userdata")) {
                        log("> erase:$p")
                        try { f.command("erase:$p", 10 * 60_000); log("wyczyszczono $p") }
                        catch (e: Exception) { log("BLAD erase:$p - ${e.message}") }
                    }
                    log("Gotowe. Pierwsze uruchomienie systemu potrwa dluzej (formatowanie).")
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------------- Franken ----------------

    private fun testErofsTools() {
        val t = pl.luki.frankenflasher.franken.ErofsTools(this, ::log)
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

    private fun erofsExtractTest(uri: Uri) {
        val write = erofsWriteTest
        Thread {
            try {
                val t = pl.luki.frankenflasher.franken.ErofsTools(this, ::log)
                val work = File(getExternalFilesDir(null), "franken_test").apply { deleteRecursively(); mkdirs() }
                val img = File(work, "in.img")
                setStep("Kopiowanie obrazu")
                contentResolver.openInputStream(uri)!!.use { i -> img.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                if (write) {
                    setStep("Zapis testowy do kopii")
                    val before = img.length()
                    pl.luki.frankenflasher.franken.ErofsPatcher(img).use { p ->
                        val dir = listOf("etc", "").first { it.isEmpty() || p.lookup(it) != null }
                        val path = (if (dir.isEmpty()) "" else "$dir/") + "franken_test.txt"
                        p.putFile(path, "Franken Flasher test\n".toByteArray())
                        log("Dodano /$path, dopisano ${p.commit()} B (obraz ${before / 1024} KB -> ${img.length() / 1024} KB)")
                    }
                    pl.luki.frankenflasher.franken.ErofsPatcher(img).use { p ->
                        val n = p.lookup("etc/franken_test.txt") ?: p.lookup("franken_test.txt")
                        if (n != null) log("Etykieta nowego pliku: " + p.xattrs(p.inode(n)).joinToString())
                    }
                    log("fsck.erofs zmienionego obrazu:")
                    log("kod fsck: " + t.run(t.fsck.path, img.path))
                }
                log("Obraz: ${img.length() / (1 shl 20)} MB -> wypakowanie")
                setStep("Wypakowanie EROFS")
                val t0 = System.currentTimeMillis()
                val out = File(work, "out")
                val rc = t.extract(img, out)
                var files = 0; var bytes = 0L
                out.walkTopDown().forEach { if (it.isFile) { files++; bytes += it.length() } }
                log("kod $rc, plikow: $files, ${bytes / (1 shl 20)} MB, ${(System.currentTimeMillis() - t0) / 1000} s")
                log("Folder: ${out.path}")
                setStep("Gotowe")
            } catch (e: Exception) { log("BLAD: ${e.message}") }
        }.start()
    }

    // ---------------- Flash ----------------

    @Suppress("DEPRECATION")
    private fun pickImage() {
        if (fb == null) { log("Najpierw nacisnij Polacz"); return }
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
                    log("Log zapisany")
                } catch (e: Exception) { log("Nie zapisano logu: ${e.message}") }
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
        if (requestCode == REQ_EROFS_TEST && resultCode == RESULT_OK) {
            data?.data?.let { erofsExtractTest(it) }
            return
        }
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val src = uriSource(uri) ?: run { log("Nie mozna odczytac pliku"); return }
        val part = partEdit.text.toString().trim().ifEmpty { src.name.substringBeforeLast('.') }
        AlertDialog.Builder(this)
            .setTitle("Flash")
            .setMessage("Wgrac ${src.name} (${src.size / (1 shl 20)} MB) na partycje \"$part\"?")
            .setPositiveButton("Flash") { _, _ ->
                withFb { f ->
                    f.flash(part, src, ::setProgress)
                    setProgress(0, 0)
                }
            }
            .setNegativeButton("Anuluj", null)
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
