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
