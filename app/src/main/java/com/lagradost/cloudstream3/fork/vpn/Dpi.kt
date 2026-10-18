package com.lagradost.cloudstream3.fork.vpn

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.fork.Domains
import com.lagradost.cloudstream3.fork.ForkFeatures
import com.lagradost.cloudstream3.fork.RemoteRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Plus > VPN > "DPI aşma": ByeDPI (hufrea/byedpi, built as lib/<abi>/libciadpi.so) runs as a small
 * SOCKS program on 127.0.0.1. It needs no server: it cuts and mixes the first packets of a
 * connection so the operator's filter does not see which site is asked for. The names of the
 * sites are found with DNS over HTTPS by Xray, which sends the connections to ByeDPI.
 */
object Dpi {
    private const val TAG = "ForkDpi"
    private const val PREFS = "fork_vpn"

    class Preset(val id: String, val name: String, val label: String, val args: String)

    /** Starting points; each falls back by itself (-A) to other ways when the site still does not open. */
    val presets = listOf(
        Preset("auto", "Otomatik", "Karışık · kendi dener", "-o1 -r-5+se -a1 -At,r,s -d1 -s1+s -a1 -At,r,s -f-1 -t8 -s1+s -a1 -At,r,s -q1 -r1+s -a1"),
        Preset("tt", "Türk Telekom", "Parçala + SNI ortası", "-s1+sm -r1+s -a1 -At,r,s -o1 -r-5+se -a1 -At,r,s -f-1 -t8 -a1"),
        Preset("tc", "Turkcell / Superonline", "Sahte paket + TTL 8", "-f-1 -t8 -s1+s -a1 -At,r,s -d1 -s1+s -a1 -At,r,s -o1 -r-5+se -a1"),
        Preset("vf", "Vodafone", "Karıştır + 1. bayt", "-d1 -s1+s -a1 -At,r,s -f-1 -t8 -a1 -At,r,s -o1 -r-5+se -a1"),
        Preset("tn", "TurkNet", "Parçala + Host", "-s1+h -d3+s -a1 -At,r,s -o1 -r-5+se -a1 -At,r,s -f-1 -t8 -a1"),
        Preset("mob", "Mobil veri", "Sahte paket + karıştır", "-f-1 -t6 -d1 -a1 -At,r,s -o1 -r-5+se -a1 -At,r,s -s1+sm -a1"),
    )

    val methods = mapOf("split" to "-s", "disorder" to "-d", "fake" to "-f", "oob" to "-o")
    val positions = mapOf("sni" to "1+sm", "first" to "1", "host" to "1+h")
    val dnsServers = mapOf(
        "cloudflare" to "https+local://1.1.1.1/dns-query",
        "google" to "https+local://8.8.8.8/dns-query",
        "adguard" to "https+local://94.140.14.14/dns-query",
        "quad9" to "https+local://9.9.9.9/dns-query",
    )

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun text(context: Context, key: String, default: String): String = store(context).getString(key, default) ?: default

    fun available(context: Context?): Boolean =
        Vpn.available(context) && ForkFeatures.isOn(RemoteRules.Feature.VPN_DPI, context)

    /** What the admin gives as the default way ("vpn_dpi_args"), when it gives one. */
    fun adminArgs(context: Context): String? =
        ForkFeatures.general(context)?.let { RemoteRules.text(it, "vpn_dpi_args") }?.trim()?.takeIf { it.isNotEmpty() }

    fun preset(context: Context): String = text(context, "dpi_preset", "auto")

    /** The arguments used now, and a short name for them. */
    fun current(context: Context): Pair<String, String> {
        val p = preset(context)
        presets.firstOrNull { it.id == p }?.let {
            if (it.id == "auto") adminArgs(context)?.let { a -> return a to "Admin ayarı" }
            return it.args to it.label
        }
        return when (p) {
            "found" -> text(context, "dpi_found", presets[0].args) to text(context, "dpi_found_name", "Bulunan ayar")
            "adv" -> {
                val cmd = text(context, "dpi_cmd", "").trim()
                if (cmd.isNotEmpty()) cmd to "Özel komut" else advanced(context)
            }
            else -> presets[0].args to presets[0].label
        }
    }

    private fun advanced(context: Context): Pair<String, String> {
        val m = text(context, "dpi_meth", "split")
        val pos = positions[text(context, "dpi_pos", "sni")] ?: "1+sm"
        val flag = methods[m] ?: "-s"
        val ttl = text(context, "dpi_ttl", "8").toIntOrNull()?.coerceIn(1, 64) ?: 8
        val args = "$flag$pos" + (if (m == "fake") " -t $ttl" else "") + " -a1"
        val name = mapOf("split" to "Parçala", "disorder" to "Karıştır", "fake" to "Sahte paket", "oob" to "Bant dışı")[m] ?: "Parçala"
        return args to name
    }

    fun dnsUrl(context: Context): String = dnsServers[text(context, "dpi_dns", "cloudflare")] ?: dnsServers.values.first()

    // ---------------------------------------------------------------- the program

    private fun binary(context: Context): java.io.File? =
        java.io.File(context.applicationInfo.nativeLibraryDir ?: return null, "libciadpi.so").takeIf { it.exists() }

    fun supported(context: Context) = binary(context) != null

    private fun split(args: String): List<String> =
        args.replace("{sni}", "google.com").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun alive(p: Process): Boolean = try {
        p.exitValue(); false
    } catch (e: IllegalThreadStateException) {
        true
    }

    private fun launch(context: Context, args: String, port: Int): Process {
        val bin = binary(context) ?: throw IllegalStateException("Bu cihaz DPI aşmayı desteklemiyor")
        val cmd = listOf(bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString()) + split(args)
        return ProcessBuilder(cmd).redirectErrorStream(true).directory(context.filesDir).start()
    }

    private fun readLast(p: Process): String = try {
        if (alive(p)) "" else p.inputStream.bufferedReader().readText().lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160)
    } catch (t: Throwable) {
        ""
    }

    private fun waitPort(p: Int, process: Process): Boolean {
        val until = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < until) {
            if (!alive(process)) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 300); return true }
            } catch (t: Throwable) {
                Thread.sleep(60)
            }
        }
        return alive(process)
    }

    @Volatile
    private var process: Process? = null

    fun start(context: Context, args: String, port: Int) {
        stop()
        val p = launch(context, args, port)
        if (!waitPort(port, p)) {
            val why = readLast(p)
            p.destroy()
            throw IllegalStateException("DPI aşma başlamadı" + if (why.isNotBlank()) ": $why" else " (ayar hatalı olabilir)")
        }
        // keep its output from filling up
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
            } catch (t: Throwable) {
            }
        }.apply { isDaemon = true }.start()
        process = p
    }

    fun stop() {
        val p = process ?: return
        process = null
        try {
            p.destroy()
        } catch (t: Throwable) {
        }
    }

    fun isAlive() = process?.let { alive(it) } == true

    // ---------------------------------------------------------------- "Otomatik bul"

    /** Sites that are blocked by the operators in Türkiye (the list of ByeByeDPI) and Discord. */
    private val testSites = listOf("discord.com", "roblox.com", "wattpad.com", "pastebin.com", "4shared.com", "wikileaks.org", "bitly.com")

    private val plain by lazy {
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    /** The real address of a site, asked over HTTPS (the operator's DNS may give a wrong one). */
    private fun resolve(host: String): String? {
        for (server in listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")) {
            try {
                val req = Request.Builder().url("$server?name=$host&type=A").header("Accept", "application/dns-json").build()
                plain.newCall(req).execute().use { res ->
                    val a = JSONObject(res.body?.string() ?: "").optJSONArray("Answer") ?: return@use
                    for (i in 0 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        if (o.optInt("type") == 1) return o.optString("data")
                    }
                }
            } catch (t: Throwable) {
            }
        }
        return null
    }

    /** Opens https://host on its real address, through ByeDPI when a port is given; the time in ms or -1. */
    private fun open(host: String, ip: String, socksPort: Int?): Long {
        val start = System.currentTimeMillis()
        val raw = if (socksPort != null) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))) else Socket()
        return try {
            raw.connect(InetSocketAddress(ip, 443), 5000)
            raw.soTimeout = 5000
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            ssl.use {
                it.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session)) return -1
                it.outputStream.write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                it.outputStream.flush()
                val line = it.inputStream.bufferedReader().readLine().orEmpty()
                if (line.startsWith("HTTP/")) System.currentTimeMillis() - start else -1
            }
        } catch (t: Throwable) {
            -1
        } finally {
            try {
                raw.close()
            } catch (t: Throwable) {
            }
        }
    }

    class Row(val index: Int, val name: String, val state: String, val note: String) {
        fun json(): JSONObject = JSONObject().put("i", index).put("name", name).put("state", state).put("note", note)
    }

    @Volatile
    var finding = false
        private set

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    private fun strategies(context: Context): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        presets.forEach { list.add(it.label + " · " + it.name to it.args) }
        try {
            context.assets.open("dpi_strategies.list").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEachIndexed { i, s -> list.add("Yöntem ${i + 1}" to s) }
        } catch (t: Throwable) {
        }
        return list
    }

    /**
     * Tries the ways one after another on blocked sites and keeps the first that opens all of them
     * (or the one that opened the most). [report] gets each row; the last one has index -1.
     */
    suspend fun find(context: Context, report: (Row) -> Unit): String? = withContext(Dispatchers.IO) {
        if (finding) return@withContext null
        finding = true
        cancelled = false
        try {
            if (!supported(context)) {
                report(Row(-1, "Desteklenmiyor", "bad", "Bu cihazda DPI aşma çalışmıyor"))
                return@withContext null
            }
            val hosts = (testSites + Domains.troubledHosts().take(3)).distinct()
            report(Row(0, "Doğrudan", "try", "engelli siteler aranıyor"))
            val resolved = coroutineScope { hosts.map { h -> async { h to resolve(h) } }.awaitAll() }.filter { it.second != null }
            if (resolved.isEmpty()) {
                report(Row(0, "Doğrudan", "bad", "İnternete ulaşılamadı"))
                report(Row(-1, "Bitti", "bad", "İnternet bağlantını kontrol et"))
                return@withContext null
            }
            val blocked = coroutineScope { resolved.map { (h, ip) -> async { Triple(h, ip!!, open(h, ip, null)) } }.awaitAll() }
                .filter { it.third < 0 }.map { it.first to it.second }
            if (blocked.isEmpty()) {
                report(Row(0, "Doğrudan", "ok", "${resolved.size} sitenin hepsi açılıyor · engel yok"))
                report(Row(-1, "Bitti", "ok", "Bu bağlantıda DPI engeli görünmüyor; mevcut ayar kalır"))
                return@withContext null
            }
            report(Row(0, "Doğrudan", "bad", "${blocked.size}/${resolved.size} engelli · " + blocked.take(3).joinToString { it.first }))

            var best: Pair<String, String>? = null
            var bestScore = 0
            var bestMs = Long.MAX_VALUE
            val list = strategies(context)
            for ((i, s) in list.withIndex()) {
                if (cancelled) break
                val row = i + 1
                report(Row(row, s.first, "try", "deneniyor · " + blocked.first().first))
                val port = java.net.ServerSocket(0).use { it.localPort }
                val p = try {
                    launch(context, s.second, port)
                } catch (t: Throwable) {
                    null
                }
                if (p == null || !waitPort(port, p)) {
                    p?.destroy()
                    report(Row(row, s.first, "bad", "başlamadı"))
                    continue
                }
                val times = try {
                    coroutineScope { blocked.map { (h, ip) -> async { open(h, ip, port) } }.awaitAll() }
                } finally {
                    p.destroy()
                }
                val ok = times.count { it >= 0 }
                val ms = times.filter { it >= 0 }.average().takeIf { !it.isNaN() }?.toLong() ?: 0
                when {
                    ok == blocked.size -> report(Row(row, s.first, "ok", "$ok/${blocked.size} açıldı · $ms ms"))
                    ok > 0 -> report(Row(row, s.first, "part", "$ok/${blocked.size} açıldı"))
                    else -> report(Row(row, s.first, "bad", "açılmadı"))
                }
                if (ok > bestScore || (ok == bestScore && ok > 0 && ms < bestMs)) {
                    best = s; bestScore = ok; bestMs = ms
                }
                if (ok == blocked.size) break
            }
            val b = best
            if (b == null) {
                report(Row(-1, "Bulunamadı", "bad", if (cancelled) "Durduruldu" else "Hiçbir yöntem açamadı · WARP'a geçiş açık kalsın"))
                return@withContext null
            }
            store(context).edit().putString("dpi_found", b.second).putString("dpi_found_name", b.first.substringBefore(" · "))
                .putString("dpi_preset", "found").apply()
            report(Row(-1, b.first, "ok", "Kaydedildi · $bestScore/${blocked.size} site açılıyor"))
            b.second
        } finally {
            finding = false
        }
    }
}
