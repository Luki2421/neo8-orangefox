package pl.luki.frankenflasher.ota

import pl.luki.frankenflasher.tr
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA z wklejonego linku (jak w OMT): bezposredni link do ZIP albo link downloadCheck z serwerow OPlus.
 * Wersja, Android i data sa czytane z META-INF/com/android/metadata zapytaniami Range (kilkadziesiat KB),
 * bez pobierania calosci. Wynik ma postac [OplusOta.Result], wiec dzialaja zwykle Pobierz
 * i Pobierz wybrane partycje.
 */
object OtaLink {
    private val URL_RE = Regex("""https?://[^\s"'<>]+""")

    /** Pierwszy link http(s) z wklejonego tekstu (np. z opisem albo w cudzyslowie). */
    fun extract(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ']', ';')

    class Loaded(val result: OplusOta.Result, val ranged: Boolean, val warnings: List<String>)

    fun load(text: String, log: (String) -> Unit): Loaded {
        val link = extract(text) ?: throw IllegalArgumentException(tr("Brak linku http(s) w wklejonym tekscie", "No http(s) link in the pasted text"))
        val url = OplusOta(log).resolve(link)
        if (url != link) log(tr("downloadCheck -> adres pliku", "downloadCheck -> file URL"))
        val fileName = try { URL(url).path.substringAfterLast('/') } catch (_: Exception) { "" }
        val warnings = ArrayList<String>()
        val props = HashMap<String, String>()
        var size = -1L
        var ranged = true
        try {
            HttpChannel(url).use { ch ->
                size = ch.size()
                try {
                    ZipLocator.readEntry(ch) { it == "META-INF/com/android/metadata" }?.second?.let { b ->
                        String(b).lines().forEach { l -> val k = l.substringBefore('=', ""); if (k.isNotEmpty()) props[k.trim()] = l.substringAfter('=').trim() }
                    }
                } catch (_: Exception) {}
                val payload = try { ZipLocator.findStored(ch, "payload.bin") != null } catch (_: Exception) { false }
                if (!payload) warnings += tr("W ZIP nie ma payload.bin - to nie jest pelne OTA A/B; pobranie mozliwe, rozpakowanie nie",
                    "No payload.bin in the ZIP - not a full A/B OTA; download works, extraction does not")
                log(tr("Odczytano z serwera ${ch.bytesRead / 1024} KB (naglowek ZIP i metadata)", "Read ${ch.bytesRead / 1024} KB from server (ZIP header and metadata)"))
            }
        } catch (e: IOException) {
            // Serwer bez odczytu fragmentow: samo pobieranie calosci nadal dziala.
            ranged = false
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 20000
            try {
                val code = c.responseCode
                if (code == 403 || code == 404 || code == 410) throw IOException(tr("HTTP $code - link wygasl albo jest bledny (linki OTA wygasaja po kilku godzinach - wygeneruj nowy)",
                    "HTTP $code - link expired or wrong (OTA links expire after a few hours - get a new one)"))
                if (code !in 200..299) throw IOException("HTTP $code")
                size = c.contentLengthLong
            } finally { c.disconnect() }
            if (size <= 0) throw IOException(tr("Serwer nie podaje rozmiaru pliku", "Server does not report the file size"))
            warnings += tr("Serwer nie obsluguje odczytu fragmentow - Wybrane partycje niedostepne, cale OTA mozna pobrac",
                "Server does not support range reads - Selected partitions unavailable, the full OTA can be downloaded")
        }
        if (!props["pre-build"].isNullOrEmpty() || !props["pre-build-incremental"].isNullOrEmpty())
            warnings += tr("To OTA przyrostowe (pre-build) - do rozpakowania potrzebne pelne OTA", "This is an incremental OTA (pre-build) - extraction needs a full OTA")
        val version = listOf("version_name", "ota_target_version", "display_id", "version_name_show")
            .firstNotNullOfOrNull { props[it]?.takeIf { s -> s.isNotBlank() } }
            ?: fileName.removeSuffix(".zip").takeIf { it.isNotBlank() }?.let { if (Regex("^[0-9a-fA-F]{24,}$").matches(it)) "ota_" + it.take(12) else it }
        val sdk = props["post-sdk-level"]?.toIntOrNull()
        val android = props["android_version"] ?: sdk?.let { if (it >= 36) (it - 20).toString() else "SDK $it" }
        val published = props["post-timestamp"]?.toLongOrNull()?.let { it * 1000 }
        val name = (version ?: "ota_link").replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(80)
        val r = OplusOta.Result(0, null, version ?: name, props["ota_target_version"], android, props["os_version"] ?: props["display_os_version"],
            props["post-security-patch-level"], published, null, listOf(OplusOta.Package("$name.zip", url, size, null)))
        return Loaded(r, ranged, warnings)
    }
}
