package pl.luki.frankenflasher.franken

import org.json.JSONObject
import pl.luki.frankenflasher.tr
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lista debloat Universal Android Debloater Next Generation (UAD-ng, spolecznosc, GitHub):
 * pakiet -> poziom (Recommended / Advanced / Expert / Unsafe) i opis. Pobierana z GitHuba,
 * zapisywana w FrankenFlasher/uad_lists.json (odswiezana co 7 dni, bez internetu - kopia).
 */
class UadList(private val cache: File, private val log: (String) -> Unit) {
    class Entry(val removal: String, val description: String, val list: String)

    companion object {
        const val URL_LIST = "https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json"
        private const val MAX_AGE = 7L * 24 * 3600 * 1000
        val LEVELS = listOf("Recommended", "Advanced", "Expert", "Unsafe")

        /**
         * Pakiet "logi i sledzenie": tylko pozycje UAD-ng z poziomem Recommended, ktorych opis mowi wprost
         * o logach, telemetrii, statystykach, diagnostyce, reklamach albo sledzeniu (OPPO/realme/OnePlus/HeyTap).
         * Bez aplikacji z funkcjami dla uzytkownika (przegladarka, sklep, chmura, motywy, odtwarzacze, zdrowie...).
         */
        /** Pakiet "Moje": domyslna lista uzytkownika (skrypt uninstall_packages MT Manager, 74 pakiety).
         *  Edytowalna w FrankenFlasher/debloat_moje.txt (jeden pakiet w linii, # komentarz). */
        val MINE_DEFAULT = listOf(
            "android.autoinstalls.config.oneplus",
            "com.android.apps.tag",
            "com.android.bips",
            "com.android.bookmarkprovider",
            "com.android.chrome",
            "com.android.egg",
            "com.android.hotwordenrollment.okgoogle",
            "com.android.hotwordenrollment.xgoogle",
            "com.android.internal.display.cutout.emulation.corner",
            "com.android.internal.display.cutout.emulation.double",
            "com.android.internal.display.cutout.emulation.hole",
            "com.android.internal.display.cutout.emulation.tall",
            "com.android.internal.display.cutout.emulation.waterfall",
            "com.android.keychain",
            "com.android.printspooler",
            "com.android.providers.calendar",
            "com.android.traceur",
            "com.coloros.activation",
            "com.coloros.assistantscreen",
            "com.coloros.compass2",
            "com.coloros.operationManual",
            "com.coloros.video",
            "com.coloros.weather.service",
            "com.facebook.appmanager",
            "com.facebook.services",
            "com.facebook.system",
            "com.google.ambient.streaming",
            "com.google.android.adservices.api",
            "com.google.android.aicore",
            "com.google.android.apps.bard",
            "com.google.android.apps.messaging",
            "com.google.android.apps.photos",
            "com.google.android.apps.safetyhub",
            "com.google.android.apps.setupwizard.searchselector",
            "com.google.android.apps.tachyon",
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.calendar",
            "com.google.android.contacts",
            "com.google.android.inputmethod.latin",
            "com.google.android.printservice.recommendation",
            "com.google.android.projection.gearhead",
            "com.google.android.setupwizard",
            "com.google.android.youtube",
            "com.google.ar.core",
            "com.heytap.accessory",
            "com.heytap.browser",
            "com.heytap.cloud",
            "com.heytap.market",
            "com.heytap.mydevices",
            "com.microsoft.appmanager",
            "com.oneplus.account",
            "com.oneplus.membership",
            "com.oneplus.oshare",
            "com.oplus.bttestmode",
            "com.oplus.cast",
            "com.oplus.games",
            "com.oplus.linker",
            "com.oplus.locationproxy",
            "com.oplus.logkit",
            "com.oplus.melody",
            "com.oplus.metis",
            "com.oplus.olc",
            "com.oplus.omoji",
            "com.oplus.pay",
            "com.oplus.phonemanager",
            "com.oplus.securepay",
            "com.oplus.securitykeyboard",
            "com.oplus.stdsp",
            "com.oplus.subsys",
            "com.oplus.themestore",
            "com.oppo.quicksearchbox",
            "com.redteamobile.roaming",
            "net.oneplus.weather",
        )

        /** Lista "Moje" z pliku; gdy go nie ma - tworzy go z [MINE_DEFAULT]. */
        fun mine(file: File): Set<String> {
            if (!file.isFile) try {
                file.parentFile?.mkdirs()
                file.writeText("# Franken Flasher - pakiet debloat \"Moje\": jedna nazwa pakietu w linii, # = komentarz\n" +
                    MINE_DEFAULT.joinToString("\n") + "\n")
            } catch (_: Exception) { return MINE_DEFAULT.toSet() }
            return try {
                file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
            } catch (_: Exception) { MINE_DEFAULT.toSet() }
        }

        val TRACKING = setOf(
            // logi i zglaszanie bledow
            "com.coloros.logkit", "com.coloros.logkit.plugin.upload", "com.oplus.logkit", "com.oppo.logkit",
            "com.oppo.logkitsdservice", "com.oppo.logkitservice", "com.realme.logtool", "com.oppo.criticallog",
            "com.oplus.olc", "com.oplus.onetrace", "com.oplus.obrain", "com.oplus.ocloud", "com.oplus.crashbox",
            "com.oplus.lfeh", "com.oppo.lfeh", "com.oplus.interconnectcollectkit", "com.oplus.powermonitor",
            "com.oplus.qualityprotect", "com.oppo.qualityprotect", "com.oppo.smartvolume", "com.coloros.feedback",
            "com.coloros.safecenter.config_plugin", "com.coloros.sauhelper", "com.oplus.postmanservice",
            "com.oneplus.opbugreportlite", "net.oneplus.commonlogtool",
            // telemetria, statystyki, program "User Experience"
            "com.oplus.statistics.rom", "com.nearme.statistics.rom", "com.nearme.deamon", "com.coloros.remoteguardservice",
            "com.oplus.metis", "com.heytap.habit.analysis", "net.oneplus.odm", "net.oneplus.odm.provider",
            // sledzenie lokalizacji, reklamy, identyfikator reklamowy, wiadomosci z trackerami
            "com.oplus.locationproxy", "com.oneplus.chargingpilar", "com.opos.ads", "com.nearme.instant.platform",
            "com.oppo.instant.local.service", "com.heytap.openid", "com.heytap.mcs",
        )
        fun short(level: String?) = when (level) {
            "Recommended" -> tr("Zalecane", "Recommended"); "Advanced" -> tr("Zaawans.", "Advanced")
            "Expert" -> tr("Ekspert", "Expert"); "Unsafe" -> tr("NIEBEZPIECZNE", "UNSAFE"); else -> tr("brak w UAD", "not in UAD")
        }
    }

    fun load(): Map<String, Entry> {
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_AGE
        if (!fresh) try {
            val c = URL(URL_LIST).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            try {
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val bytes = c.inputStream.use { it.readBytes() }
                JSONObject(String(bytes)) // poprawny JSON przed nadpisaniem kopii
                cache.parentFile?.mkdirs()
                File(cache.path + ".tmp").also { it.writeBytes(bytes) }.renameTo(cache)
                log(tr("UAD-ng: pobrano liste debloat (${bytes.size / 1024} KB)", "UAD-ng: debloat list downloaded (${bytes.size / 1024} KB)"))
            } finally { c.disconnect() }
        } catch (e: Exception) {
            log(tr("UAD-ng: nie udalo sie pobrac listy (${e.message})" + if (cache.isFile) " - uzywam zapisanej kopii" else "",
                "UAD-ng: could not download the list (${e.message})" + if (cache.isFile) " - using the saved copy" else ""))
        }
        if (!cache.isFile) return emptyMap()
        return try {
            val o = JSONObject(cache.readText())
            val out = HashMap<String, Entry>(o.length() * 2)
            for (k in o.keys()) {
                val v = o.optJSONObject(k) ?: continue
                out[k] = Entry(v.optString("removal"), v.optString("description").trim(), v.optString("list"))
            }
            out
        } catch (e: Exception) { log(tr("UAD-ng: uszkodzona lista (${e.message})", "UAD-ng: broken list (${e.message})")); emptyMap() }
    }
}
