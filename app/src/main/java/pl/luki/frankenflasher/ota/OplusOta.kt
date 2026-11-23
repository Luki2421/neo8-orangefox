package pl.luki.frankenflasher.ota

import pl.luki.frankenflasher.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wyszukiwanie i pobieranie pelnych OTA z serwerow aktualizacji OPlus (realme / OPPO / OnePlus),
 * protokol update/v6 (ten sam co OplusUpdater / OPlus-Tracker - wlasna implementacja w Kotlinie).
 */
class OplusOta(private val log: (String) -> Unit) {

    class Region(val id: String, val host: String, val carrier: String, val lang: String, val key: String, val keyVer: String)

    class Package(val name: String, val url: String, val size: Long, val md5: String?)

    class Result(
        val code: Int, val err: String?,
        val versionName: String?, val otaVersion: String?, val android: String?, val os: String?,
        val patch: String?, val published: Long?, val changelog: String?, val packages: List<Package>,
    ) {
        fun best(): Package? = packages.maxByOrNull { it.size }
    }

    companion object {
        private val KEYS = mapOf(
            "CN" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApXYGXQpNL7gmMzzvajHaoZIHQQvBc2cOEhJc7/tsaO4sT0unoQnwQKfNQCuv7qC1Nu32eCLuewe9LSYhDXr9KSBWjOcCFXVXteLO9WCaAh5hwnUoP/5/Wz0jJwBA+yqs3AaGLA9wJ0+B2lB1vLE4FZNE7exUfwUc03fJxHG9nCLKjIZlrnAAHjRCd8mpnADwfkCEIPIGhnwq7pdkbamZcoZfZud1+fPsELviB9u447C6bKnTU4AaMcR9Y2/uI6TJUTcgyCp+ilgU0JxemrSIPFk3jbCbzamQ6Shkw/jDRzYoXpBRg/2QDkbq+j3ljInu0RHDfOeXf3VBfHSnQ66HCwIDAQAB",
            "IN" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwYtghkzeStC9YvAwOQmWylbp74Tj8hhi3f9IlK7A/CWrGbLgzz/BeKxNb45zBN8pgaaEOwAJ1qZQV5G4nProWCPOP1ro1PkemFJvw/vzOOT5uN0ADnHDzZkZXCU/knxqUSfLcwQlHXsYhNsAm7uOKjY9YXF4zWzYN0eFPkML3Pj/zg7hl/ov9clB2VeyI1/blMHFfcNA/fvqDTENXcNBIhgJvXiCpLcZqp+aLZPC5AwY/sCb3j5jTWer0Rk0ZjQBZE1AncwYvUx4mA65U59cWpTyl4c47J29MsQ66hqWv6eBHlDNZSEsQpHePUqgsf7lmO5Wd7teB8ugQki2oz1Y5QIDAQAB",
            "EU" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh8/EThsK3f0WyyPgrtXb/D0Xni6UZNppaQHUqHWo976cybl92VxmehE0ISObnxERaOtrlYmTPIxkVC9MMueDvTwZ1l0KxevZVKU0sJRxNR9AFcw6D7k9fPzzpNJmhSlhpNbt3BEepdgibdRZbacF3NWy3ejOYWHgxC+I/Vj1v7QU5gD+1OhgWeRDcwuV4nGY1ln2lvkRj8EiJYXfkSq/wUI5AvPdNXdEqwou4FBcf6mD84G8pKDyNTQwwuk9lvFlcq4mRqgYaFg9DAgpDgqVK4NTJWM7tQS1GZuRA6PhupfDqnQExyBFhzCefHkEhcFywNyxlPe953NWLFWwbGvFKwIDAQAB",
            "SG" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkA980wxi+eTGcFDiw2I6RrUeO4jL/Aj3Yw4dNuW7tYt+O1sRTHgrzxPD9SrOqzz7G0KgoSfdFHe3JVLPN+U1waK+T0HfLusVJshDaMrMiQFDUiKajb+QKr+bXQhVofH74fjat+oRJ8vjXARSpFk4/41x5j1Bt/2bHoqtdGPcUizZ4whMwzap+hzVlZgs7BNfepo24PWPRujsN3uopl+8u4HFpQDlQl7GdqDYDj2zNOHdFQI2UpSf0aIeKCKOpSKF72KDEESpJVQsqO4nxMwEi2jMujQeCHyTCjBZ+W35RzwT9+0pyZv8FB3c7FYY9FdF/+lvfax5mvFEBd9jO+dpMQIDAQAB",
        )
        private const val CN = "component-ota-cn.allawntech.com"
        private const val EU = "component-ota-eu.allawnos.com"
        private const val IN = "component-ota-in.allawnos.com"
        private const val SG = "component-ota-sg.allawnos.com"
        private const val SGV = "1615895993238"

        val REGIONS = listOf(
            Region("CN", CN, "10010111", "zh-CN", KEYS.getValue("CN"), "1615879139745"),
            Region("EU", EU, "01000100", "en-GB", KEYS.getValue("EU"), "1615897067573"),
            Region("IN", IN, "00011011", "en-IN", KEYS.getValue("IN"), "1615896309308"),
            Region("GL", SG, "10100111", "en-US", KEYS.getValue("SG"), SGV),
            Region("SG", SG, "01011010", "en-SG", KEYS.getValue("SG"), SGV),
            Region("RU", SG, "00110111", "ru-RU", KEYS.getValue("SG"), SGV),
            Region("TR", SG, "01010001", "tr-TR", KEYS.getValue("SG"), SGV),
            Region("TH", SG, "00111001", "th-TH", KEYS.getValue("SG"), SGV),
            Region("ID", SG, "00110011", "id-ID", KEYS.getValue("SG"), SGV),
            Region("TW", SG, "00011010", "zh-TW", KEYS.getValue("SG"), SGV),
            Region("MY", SG, "00111000", "ms-MY", KEYS.getValue("SG"), SGV),
            Region("VN", SG, "00111100", "vi-VN", KEYS.getValue("SG"), SGV),
        )

        fun region(id: String) = REGIONS.firstOrNull { it.id == id } ?: REGIONS[0]

        /** "RMX8899" / "RMX8899_11.A" -> "RMX8899_11.A.01_0001_197001010000" (najstarsza wersja = serwer zwraca pelny pakiet). */
        fun otaVersion(input: String): String {
            var v = input.trim()
            if (!v.contains('_')) v += "_11.A"
            if (v.split('_').size < 3 || v.split('.').size < 3) v += ".01_0001_197001010000"
            return v
        }

        fun defaultModel(otaVersion: String, region: String): String {
            val base = otaVersion.substringBefore('_')
            return base + when (region) { "EU" -> "EEA"; "IN" -> "IN"; else -> "" }
        }
    }

    private val rnd = SecureRandom()

    fun query(input: String, regionId: String, modelIn: String?, taste: Boolean): Result {
        val r = region(regionId)
        val ota = otaVersion(input)
        val model = modelIn?.trim()?.takeIf { it.isNotEmpty() } ?: defaultModel(ota, r.id)
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val b64 = Base64.getEncoder()

        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(r.key)))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply { init(Cipher.ENCRYPT_MODE, pub) }
        val protectedKey = b64.encodeToString(rsa.doFinal(b64.encodeToString(key).toByteArray()))
        val deviceId = ByteArray(32).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        val payload = JSONObject()
            .put("mode", "0").put("time", System.currentTimeMillis()).put("isRooted", "0").put("isLocked", true)
            .put("type", "0").put("deviceId", deviceId).put("opex", JSONObject().put("check", true))
        val body = JSONObject().put("cipher", b64.encodeToString(aes(Cipher.ENCRYPT_MODE, key, iv, payload.toString().toByteArray())))
            .put("iv", b64.encodeToString(iv))
        val req = JSONObject().put("params", body.toString()).toString()
        val pk = JSONObject().put("SCENE_1", JSONObject().put("protectedKey", protectedKey)
            .put("version", ((System.currentTimeMillis() + 86_400_000L) * 1_000_000L).toString()).put("negotiationVersion", r.keyVer))

        log(tr("OTA: zapytanie $model / $ota / ${r.id}${if (taste) " (beta)" else ""}", "OTA: query $model / $ota / ${r.id}${if (taste) " (beta)" else ""}"))
        val c = URL("https://${r.host}/update/v6").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.requestMethod = "POST"; c.doOutput = true
        mapOf(
            "language" to r.lang, "androidVersion" to "unknown", "colorOSVersion" to "unknown", "otaVersion" to ota,
            "model" to model, "mode" to if (taste) "taste" else "manual", "nvCarrier" to r.carrier, "version" to "2",
            "deviceId" to deviceId, "protectedKey" to pk.toString(), "Content-Type" to "application/json; charset=utf-8",
        ).forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(req.toByteArray()) }
        val http = c.responseCode
        val text = ((if (http in 200..299) c.inputStream else c.errorStream) ?: throw java.io.IOException("HTTP $http"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()

        val res = JSONObject(text)
        val code = res.optInt("responseCode", http)
        val err = res.optString("errMsg").takeIf { it.isNotEmpty() && it != "null" }
        val bodyStr = res.opt("body")?.takeIf { it != JSONObject.NULL }?.toString()
        if (bodyStr.isNullOrEmpty()) return Result(code, err ?: tr("brak danych", "no data"), null, null, null, null, null, null, null, emptyList())
        val enc = JSONObject(bodyStr)
        val plain = aes(Cipher.DECRYPT_MODE, key, Base64.getDecoder().decode(enc.getString("iv")), Base64.getDecoder().decode(enc.getString("cipher")))
        val j = JSONObject(String(plain, Charsets.UTF_8))

        val pkgs = ArrayList<Package>()
        val arr = j.optJSONArray("components") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val comp = arr.optJSONObject(i) ?: continue
            val p = comp.optJSONObject("componentPackets") ?: continue
            val url = p.optString("url").takeIf { it.startsWith("http") } ?: p.optString("manualUrl").takeIf { it.startsWith("http") } ?: continue
            pkgs += Package(comp.optString("componentName", "ota"), url, p.optString("size").toLongOrNull() ?: 0L,
                p.optString("md5").takeIf { it.length == 32 })
        }
        fun s(k: String) = j.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        val desc = j.optJSONObject("description")
        return Result(code, err, s("realVersionName") ?: s("versionName"), s("realOtaVersion") ?: s("otaVersion"),
            s("realAndroidVersion") ?: s("androidVersion"), s("realOsVersion") ?: s("osVersion") ?: s("colorOSVersion"),
            s("securityPatch"), j.optLong("publishedTime").takeIf { it > 0 },
            desc?.optString("panelUrl")?.takeIf { it.isNotEmpty() } ?: desc?.optString("url")?.takeIf { it.isNotEmpty() }, pkgs)
    }

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)

    /** Adresy .../downloadCheck wymagaja naglowka userid i podaja wlasciwy adres w przekierowaniu 302. */
    fun resolve(url: String): String {
        if (!url.contains("/downloadCheck")) return url
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("userid", "oplus-ota|")
        c.connectTimeout = 15000; c.readTimeout = 15000
        try {
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            if (code in 300..399 && !loc.isNullOrEmpty()) return loc
            throw java.io.IOException(tr("downloadCheck: kod $code, brak przekierowania", "downloadCheck: code $code, no redirect"))
        } finally { c.disconnect() }
    }

    /** Pobieranie z wznawianiem (plik .part) i sprawdzeniem MD5. [cancel] przerywa (plik .part zostaje). */
    /**
     * [requery] - ponowne zapytanie, ktore dalo ten pakiet (Szukaj / archiwum); uzywane, gdy podpisany
     * link wygasnie w trakcie pobierania: nowy downloadCheck tego samego pakietu (ta sama suma MD5 / rozmiar).
     * Bez niego (link wklejony) - ponowne rozwiniecie tego samego downloadCheck.
     */
    fun download(pkg: Package, out: File, cancel: () -> Boolean, progress: (Long, Long) -> Unit, requery: (() -> Result?)? = null): File {
        out.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        val map = FastDownload.mapFile(part)
        var have = if (part.isFile) part.length() else 0L
        // Plik pelnej dlugosci bez mapy kawalkow = pobrany (z mapa: pobieranie wielowatkowe w toku).
        if (!(pkg.size > 0 && have == pkg.size && !map.exists())) {
            val url = resolve(pkg.url)
            val refresh = { n: Int ->
                val fresh = if (requery != null && n % 2 == 1) requery()?.packages?.firstOrNull { p ->
                    p.size == pkg.size && (pkg.md5 == null || p.md5 == null || p.md5.equals(pkg.md5, ignoreCase = true))
                } else null
                resolve((fresh ?: pkg).url)
            }
            if (pkg.size > 0 && FastDownload.run(url, part, pkg.size, FastDownload.THREADS, cancel, log, progress, refresh)) {
                have = pkg.size
            } else {
            if (map.exists()) { map.delete(); part.delete(); have = 0 } // serwer bez Range: od poczatku
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code !in 200..299) { c.disconnect(); throw java.io.IOException(tr("Pobieranie: HTTP $code", "Download: HTTP $code")) }
            if (code == 200 && have > 0) { log(tr("Serwer nie wznawia - pobieram od poczatku", "Server can't resume - downloading from start")); have = 0 }
            else if (have > 0) log(tr("Wznawiam od ${have / (1 shl 20)} MB", "Resuming at ${have / (1 shl 20)} MB"))
            val total = if (pkg.size > 0) pkg.size else have + c.contentLengthLong.coerceAtLeast(0)
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(have); raf.seek(have)
                    c.inputStream.use { ins ->
                        val buf = ByteArray(1 shl 20)
                        var last = 0L
                        while (true) {
                            if (cancel()) throw InterruptedException(tr("Pobieranie przerwane - Pobierz wznowi je od ${have / (1 shl 20)} MB",
                                "Download stopped - Download resumes at ${have / (1 shl 20)} MB"))
                            val n = ins.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); have += n
                            if (have - last >= (8 shl 20)) { progress(have, total); last = have }
                        }
                    }
                }
            } finally { c.disconnect() }
            }
            if (pkg.size > 0 && part.length() != pkg.size) throw java.io.IOException(tr("Niepelny plik: ${part.length() / (1 shl 20)} z ${pkg.size / (1 shl 20)} MB - nacisnij Pobierz, aby wznowic",
                "Incomplete: ${part.length() / (1 shl 20)} of ${pkg.size / (1 shl 20)} MB - tap Download to resume"))
        }
        pkg.md5?.let { m ->
            log(tr("Sprawdzanie MD5...", "Verifying MD5..."))
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(4 shl 20); var done = 0L; var last = 0L
            part.inputStream().use { i ->
                while (true) {
                    if (cancel()) throw InterruptedException(tr("Przerwano sprawdzanie MD5", "MD5 check stopped"))
                    val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n
                    if (done - last >= (64 shl 20)) { progress(done, part.length()); last = done }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(m, true)) { part.delete(); throw java.io.IOException(tr("Zla suma MD5 - plik usuniety, pobierz ponownie", "Bad MD5 - file deleted, download again")) }
            log(tr("MD5 zgodne", "MD5 OK"))
        }
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) throw java.io.IOException(tr("Nie mozna zmienic nazwy ${part.name}", "Cannot rename ${part.name}"))
        return out
    }
}
