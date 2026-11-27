package pl.luki.frankenflasher.ota

import pl.luki.frankenflasher.tr
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pobieranie wieloma polaczeniami naraz (jak IDM / aria2): plik dzielony na kawalki po 16 MB,
 * [threads] watkow pobiera kolejne kawalki zapytaniami Range i zapisuje je na swoje miejsce w pliku .part.
 * Ukonczone kawalki sa dopisywane do pliku .part.map, wiec przerwanie (albo wygasly link) nie traci
 * pobranych danych - wznowienie pobiera tylko brakujace kawalki.
 */
object FastDownload {
    const val CHUNK = 16L shl 20
    const val THREADS = 8
    /** Serwery w Chinach: duze opoznienie i gubione pakiety z Europy - jedno polaczenie ~0.45 MB/s, wiec wiecej polaczen. */
    const val THREADS_CN = 16

    private val CN_HOST = Regex("""(^|[.-])cn([.-]|$)""")

    fun isChinaHost(url: String): Boolean = try {
        val h = java.net.URL(url).host.lowercase()
        h.endsWith("allawntech.com") || CN_HOST.containsMatchIn(h)
    } catch (_: Exception) { false }

    /** Liczba polaczen dla pakietu: 16 dla serwerow CN, 8 dla pozostalych. */
    fun threadsFor(vararg urls: String): Int = if (urls.any { isChinaHost(it) }) THREADS_CN else THREADS

    fun mapFile(part: File) = File(part.path + ".map")

    /** Ile bajtow juz pobrano (z mapy kawalkow albo z dlugosci starego pliku pobieranego jednym polaczeniem). */
    fun downloadedBytes(part: File, size: Long = -1): Long {
        val map = mapFile(part)
        if (!map.isFile) return if (part.isFile) part.length() else 0L
        val total = if (size > 0) size else part.length()
        return readMap(map).sumOf { i -> minOf(CHUNK, total - i * CHUNK).coerceAtLeast(0) }
    }

    private fun readMap(map: File): Set<Int> =
        try { map.readLines().mapNotNull { it.trim().toIntOrNull() }.toSet() } catch (_: Exception) { emptySet() }

    /** Czy serwer oddaje fragmenty (206 na Range). */
    fun supportsRange(url: String): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 20000
        c.setRequestProperty("Range", "bytes=0-0")
        c.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = c.responseCode
            if (code == 206) return true
            if (code in 200..299) return false
            throw IOException(tr("Pobieranie: HTTP $code", "Download: HTTP $code") +
                if (code == 403 || code == 404 || code == 410) tr(" - link wygasl, Szukaj albo Wczytaj link ponownie", " - link expired, Search or Load link again") else "")
        } finally { c.disconnect() }
    }

    /**
     * Pobiera [url] do [part] (dokladnie [size] bajtow). Zwraca false, gdy serwer nie obsluguje Range
     * (nic nie zostalo zmienione - wtedy pobieranie jednym polaczeniem). [cancel] przerywa wyjatkiem
     * InterruptedException; pobrane kawalki zostaja.
     * [refresh] - nowy adres, gdy podpisany link wygasnie w trakcie (serwery OPlus: ~10 min);
     * pobieranie trwa dalej bez udzialu uzytkownika (max [MAX_REFRESH] razy).
     */
    fun run(url: String, part: File, size: Long, threads: Int, cancel: () -> Boolean,
            log: (String) -> Unit, progress: (Long, Long) -> Unit, refresh: ((Int) -> String)? = null): Boolean {
        require(size > 0)
        val link = Link(url, refresh, log)
        val ranged = try { supportsRange(link.url) } catch (e: IOException) {
            // link wygasl zanim pobieranie ruszylo (np. dlugo po Szukaj) - jedna proba z nowym adresem
            if (refresh == null || e.message?.let { "link wygasl" in it || "link expired" in it } != true) throw e
            supportsRange(link.renew(link.url))
        }
        if (!ranged) return false
        val n = ((size + CHUNK - 1) / CHUNK).toInt()
        val map = mapFile(part)
        val done = BooleanArray(n)
        if (map.isFile) {
            for (i in readMap(map)) if (i in 0 until n) done[i] = true
        } else if (part.isFile && part.length() in 1 until size) {
            // stare pobieranie jednym polaczeniem: ciagly poczatek pliku jest gotowy
            for (i in 0 until (part.length() / CHUNK).toInt()) done[i] = true
        } else if (part.isFile) part.delete()
        part.parentFile?.mkdirs()
        // Mapa powstaje przed powiekszeniem pliku: plik pelnej dlugosci bez mapy znaczy "pobrane".
        map.writeText((0 until n).filter { done[it] }.joinToString("") { "$it\n" })
        java.io.RandomAccessFile(part, "rw").use { if (it.length() != size) it.setLength(size) }

        val pending = (0 until n).filter { !done[it] }
        val got = AtomicLong(downloadedBytes(part, size))
        val startBytes = got.get()
        if (pending.isEmpty()) { map.delete(); return true }
        val workers = minOf(threads, pending.size).coerceAtLeast(1)
        val cn = if (threads >= THREADS_CN) tr(" (serwer w Chinach)", " (server in China)") else ""
        log(tr("Pobieranie: $workers polaczen$cn, ${pending.size} z $n kawalkow po ${CHUNK shr 20} MB",
            "Downloading: $workers connections$cn, ${pending.size} of $n chunks of ${CHUNK shr 20} MB"))
        progress(startBytes, size)
        val next = AtomicInteger(0)
        val failed = AtomicReference<Throwable>()
        val lastReport = AtomicLong(0)
        val t0 = System.currentTimeMillis()
        val report = { val g = got.get(); val l = lastReport.get(); if (g - l >= (2 shl 20) && lastReport.compareAndSet(l, g)) progress(g, size) }
        FileChannel.open(part.toPath(), StandardOpenOption.WRITE).use { ch ->
            FileOutputStream(map, true).use { mapOut ->
                val pool = (0 until workers).map {
                    Thread {
                        val buf = ByteArray(256 shl 10)
                        try {
                            while (failed.get() == null && !cancel()) {
                                val k = next.getAndIncrement()
                                if (k >= pending.size) break
                                val i = pending[k]
                                if (fetchChunk(link, ch, i, size, buf, cancel, got, report)) {
                                    synchronized(mapOut) { mapOut.write("$i\n".toByteArray()); mapOut.flush() }
                                }
                            }
                        } catch (e: Throwable) { failed.compareAndSet(null, e) }
                    }.apply { name = "dl-$it"; start() }
                }
                pool.forEach { it.join() }
            }
            ch.force(false)
        }
        failed.get()?.let { throw it }
        val have = got.get()
        val secs = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.001)
        log(tr("Srednio %.1f MB/s".format((have - startBytes) / secs / (1 shl 20)), "Average %.1f MB/s".format((have - startBytes) / secs / (1 shl 20))))
        if (cancel()) throw InterruptedException(tr("Pobieranie przerwane - pobrano ${have / (1 shl 20)} z ${size / (1 shl 20)} MB, Pobierz wznowi brakujace kawalki",
            "Download stopped - ${have / (1 shl 20)} of ${size / (1 shl 20)} MB, Download resumes the missing chunks"))
        val missing = (0 until n).toSet() - readMap(map)
        if (missing.isNotEmpty()) throw IOException(tr("Brakuje ${missing.size} kawalkow - nacisnij Pobierz, aby dokonczyc",
            "${missing.size} chunks missing - tap Download to finish"))
        map.delete()
        progress(size, size)
        return true
    }

    const val MAX_REFRESH = 30

    /** Wspolny dla watkow adres; odswiezany raz na wygasniecie (pierwszy watek z 403 pobiera nowy). */
    private class Link(@Volatile var url: String, val refresh: ((Int) -> String)?, val log: (String) -> Unit) {
        var count = 0
        var broken: IOException? = null
        @Synchronized fun renew(failed: String): String {
            broken?.let { throw it }
            if (url != failed) return url
            if (refresh == null || count >= MAX_REFRESH) throw ExpiredException()
            count++
            log(tr("Link wygasl - pobieram nowy adres ($count)...", "Link expired - getting a new URL ($count)..."))
            url = try { refresh.invoke(count).also { if (it == failed) throw IOException(tr("serwer zwrocil ten sam wygasly adres", "server returned the same expired URL")) } } catch (e: Exception) {
                throw broken ?: IOException(tr("link wygasl i nie udalo sie go odswiezyc (${e.message}) - Szukaj albo Wczytaj link ponownie, Pobierz wznowi",
                    "link expired and could not be refreshed (${e.message}) - Search or Load link again, Download resumes"), e).also { broken = it }
            }
            return url
        }
    }

    private class ExpiredException : IOException(tr("link wygasl, Szukaj albo Wczytaj link ponownie", "link expired, Search or Load link again"))

    /** Jeden kawalek; ponawianie od miejsca zerwania (5 prob). false = przerwane przez [cancel]. */
    private fun fetchChunk(link: Link, ch: FileChannel, i: Int, size: Long, buf: ByteArray, cancel: () -> Boolean, got: AtomicLong, report: () -> Unit): Boolean {
        val start = i * CHUNK
        val end = minOf(size, start + CHUNK)
        var pos = start
        var attempt = 0
        while (pos < end) {
            if (cancel()) return false
            val url = link.url
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("Range", "bytes=$pos-${end - 1}")
            c.setRequestProperty("Accept-Encoding", "identity")
            try {
                val code = c.responseCode
                if (code == 403 || code == 404 || code == 410) {
                    try { link.renew(url) } catch (e: ExpiredException) {
                        throw IOException(tr("HTTP $code przy kawalku $i - ", "HTTP $code for chunk $i - ") + e.message)
                    }
                    continue
                }
                if (code != 206) throw IOException(tr("HTTP $code przy kawalku $i", "HTTP $code for chunk $i"))
                val cr = c.getHeaderField("Content-Range")
                if (cr != null && !cr.startsWith("bytes $pos-")) throw IOException("Content-Range: $cr")
                c.inputStream.use { ins ->
                    while (pos < end) {
                        if (cancel()) return false
                        val r = ins.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                        if (r < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, r)
                        var at = pos
                        while (bb.hasRemaining()) at += ch.write(bb, at)
                        pos += r
                        got.addAndGet(r.toLong())
                        report()
                        attempt = 0
                    }
                }
                if (pos < end) throw IOException(tr("Polaczenie zerwane", "Connection dropped"))
            } catch (e: IOException) {
                if (e.message?.contains("link wygasl") == true || e.message?.contains("link expired") == true) throw e
                // Serwer CDN z adresu przestal istniec w DNS (log: "Unable to resolve host gauss-compota-c-in...") -
                // po jednej nieudanej probie nowy adres (downloadCheck zwykle daje inny serwer).
                if (e is java.net.UnknownHostException && attempt >= 1 && link.refresh != null) {
                    link.renew(url); attempt = 0; continue
                }
                if (++attempt >= 5) throw e
                Thread.sleep(1000L * attempt)
            } finally { c.disconnect() }
        }
        return true
    }
}
