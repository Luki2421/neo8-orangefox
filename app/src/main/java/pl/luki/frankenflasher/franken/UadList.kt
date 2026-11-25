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
