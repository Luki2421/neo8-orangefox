package pl.luki.frankenflasher.ota

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lokalne archiwum znalezionych wersji OTA (serwer OPlus podaje tylko najnowsza wersje -
 * kazde wyszukiwanie dopisuje wynik, wiec historia rosnie z czasem).
 * Plik JSON w FrankenFlasher/ota_archive.json (przezywa reinstalacje aplikacji).
 */
class OtaArchive(private val file: File) {

    class Entry(
        val model: String, val region: String, val taste: Boolean,
        val versionName: String, val otaVersion: String, val android: String?, val os: String?,
        val patch: String?, val published: Long?, val size: Long, val md5: String?, val url: String,
        val changelog: String?, val found: Long,
    ) {
        val key get() = md5?.lowercase() ?: "$otaVersion|$taste"

        fun toResult() = OplusOta.Result(200, null, versionName, otaVersion, android, os, patch, published, changelog,
            listOf(OplusOta.Package("ota", url, size, md5)))

        fun toJson(): JSONObject = JSONObject().put("model", model).put("region", region).put("taste", taste)
            .put("versionName", versionName).put("otaVersion", otaVersion).put("android", android).put("os", os)
            .put("patch", patch).put("published", published ?: 0L).put("size", size).put("md5", md5).put("url", url)
            .put("changelog", changelog).put("found", found)

        companion object {
            fun of(j: JSONObject): Entry {
                fun s(k: String) = j.optString(k).takeIf { it.isNotEmpty() && it != "null" }
                return Entry(j.optString("model"), j.optString("region"), j.optBoolean("taste"), j.optString("versionName"),
                    j.optString("otaVersion"), s("android"), s("os"), s("patch"), j.optLong("published").takeIf { it > 0 },
                    j.optLong("size"), s("md5"), j.optString("url"), s("changelog"), j.optLong("found"))
            }
        }
    }

    @Synchronized
    fun load(): MutableList<Entry> = try {
        if (!file.isFile) ArrayList() else JSONArray(file.readText()).let { a -> (0 until a.length()).map { Entry.of(a.getJSONObject(it)) }.toMutableList() }
    } catch (_: Exception) { ArrayList() }

    /** Dopisuje wyniki; istniejacy wpis (ten sam MD5/wersja) dostaje nowszy adres i liste regionow. Zwraca liczbe nowych. */
    @Synchronized
    fun add(model: String, results: List<Triple<String, Boolean, OplusOta.Result>>): Int {
        val all = load()
        val byKey = all.associateBy { it.key }.toMutableMap()
        var added = 0
        val now = System.currentTimeMillis()
        for ((region, taste, r) in results) {
            val p = r.best() ?: continue
            val e = Entry(model.substringBefore('_').uppercase(), region, taste, r.versionName ?: r.otaVersion ?: "?", r.otaVersion ?: "",
                r.android, r.os, r.patch, r.published, p.size, p.md5, p.url, r.changelog, now)
            val old = byKey[e.key]
            if (old == null) { byKey[e.key] = e; added++ }
            else {
                val regs = (old.region.split('/') + region.split('/')).filter { it.isNotEmpty() }.distinct().joinToString("/")
                byKey[e.key] = Entry(old.model, regs, old.taste, old.versionName, old.otaVersion, old.android, old.os, old.patch,
                    old.published ?: e.published, old.size, old.md5 ?: e.md5, e.url, e.changelog ?: old.changelog, old.found)
            }
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONArray(byKey.values.map { it.toJson() }).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {}
        return added
    }

    fun forModel(model: String): List<Entry> {
        val m = model.substringBefore('_').uppercase()
        return load().filter { m.isEmpty() || it.model == m || it.model.startsWith(m) }
            .sortedWith(compareByDescending<Entry> { it.published ?: 0L }.thenByDescending { it.otaVersion })
    }
}
