package com.lagradost.cloudstream3.fork.vpn

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.fork.Domains
import com.lagradost.cloudstream3.fork.ForkFeatures
import com.lagradost.cloudstream3.fork.RemoteRules
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * A VPN for this app only (Plus > VPN). Xray runs inside the app with a proxy port on 127.0.0.1;
 * the requests of the app (and, when asked, the videos) go through it, the rest of the phone does
 * not. No Android VPN permission is needed. Servers: Cloudflare WARP (free), the user's own
 * (WireGuard / VLESS / VMess / Trojan / Shadowsocks), or one the admin gives ("vpn_server").
 */
object Vpn {
    private const val TAG = "ForkVpn"
    private const val PREFS = "fork_vpn"
    const val MODE_WARP = "warp"
    const val MODE_OWN = "own"
    const val MODE_ADMIN = "admin"
    const val SCOPE_SMART = "smart"
    const val SCOPE_SITES = "sites"
    const val SCOPE_ALL = "all"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var running = false
        private set

    @Volatile
    private var port = 0

    @Volatile
    var since = 0L
        private set

    private var bytesAtStart = 0L

    @Volatile
    var lastError: String? = null

    @Volatile
    var onState: (() -> Unit)? = null

    private var appContext: Context? = null

    // what the selectors read on every request
    @Volatile
    private var scopeNow = SCOPE_SMART

    @Volatile
    private var failOpen = true

    @Volatile
    private var autoOpen = true

    private val learned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var troubled: Set<String> = emptySet()

    @Volatile
    private var troubledAt = 0L

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.VPN, context)

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- settings

    fun mode(context: Context): String = store(context).getString("mode", MODE_WARP) ?: MODE_WARP
    fun scopeOf(context: Context): String = store(context).getString("scope", SCOPE_SMART) ?: SCOPE_SMART
    fun flag(context: Context, key: String, default: Boolean) = store(context).getBoolean(key, default)

    fun set(context: Context, key: String, value: Any) {
        val e = store(context).edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            else -> e.putString(key, value.toString())
        }
        e.apply()
        readSettings(context)
    }

    private fun readSettings(context: Context) {
        scopeNow = scopeOf(context)
        failOpen = flag(context, "failopen", true)
        autoOpen = flag(context, "smart", true)
        try {
            val a = JSONArray(store(context).getString("learned", "[]"))
            learned.clear()
            for (i in 0 until a.length()) learned.add(a.getString(i))
        } catch (t: Throwable) {
        }
    }

    private fun saveLearned(context: Context) {
        store(context).edit().putString("learned", JSONArray(learned.toList().takeLast(200)).toString()).apply()
    }

    fun learnedCount() = learned.size

    fun clearLearned(context: Context) {
        learned.clear()
        saveLearned(context)
    }

    // ---------------------------------------------------------------- servers

    class Server(val id: String, val name: String, val kind: String, val raw: String, val host: String, val port: Int) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("raw", raw).put("host", host).put("port", port)
    }

    fun servers(context: Context): List<Server> = try {
        val a = JSONArray(store(context).getString("servers", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Server(it.optString("id"), it.optString("name"), it.optString("kind"), it.optString("raw"), it.optString("host"), it.optInt("port"))
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun saveServers(context: Context, list: List<Server>) {
        store(context).edit().putString("servers", JSONArray(list.map { it.json() }).toString()).apply()
    }

    fun selected(context: Context): String? = store(context).getString("selected", null)

    fun addServer(context: Context, raw: String, name: String?): Server? {
        val p = VpnConfig.parse(raw) ?: return null
        val s = Server(System.currentTimeMillis().toString(36), name?.trim()?.ifBlank { null } ?: p.name, p.kind, raw.trim(), p.host, p.port)
        saveServers(context, servers(context) + s)
        store(context).edit().putString("selected", s.id).apply()
        return s
    }

    fun removeServer(context: Context, id: String) {
        val list = servers(context).filter { it.id != id }
        saveServers(context, list)
        if (selected(context) == id) store(context).edit().putString("selected", list.firstOrNull()?.id).apply()
    }

    fun select(context: Context, id: String) = store(context).edit().putString("selected", id).apply()

    /** The admin's server: "vpn_server" (a link or a WireGuard .conf), "vpn_server_name", "vpn_server_on" */
    fun adminServer(context: Context): Pair<String, String>? {
        val g = ForkFeatures.general(context) ?: return null
        if (!RemoteRules.flagOf(g, true, "vpn_server_on")) return null
        val raw = RemoteRules.text(g, "vpn_server")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = RemoteRules.text(g, "vpn_server_name")?.takeIf { it.isNotBlank() } ?: VpnConfig.parse(raw)?.name ?: "Admin"
        return name to raw
    }

    private fun outbound(context: Context, mode: String = mode(context), serverId: String? = null): JSONObject = when (mode) {
        MODE_WARP -> Warp.outbound(context)
        MODE_ADMIN -> {
            val raw = adminServer(context)?.second ?: throw IllegalStateException("Admin sunucu vermedi")
            VpnConfig.parse(raw)?.outbound ?: throw IllegalStateException("Admin sunucusu okunamadı")
        }
        else -> {
            val list = servers(context)
            val s = list.firstOrNull { it.id == (serverId ?: selected(context)) } ?: list.firstOrNull()
                ?: throw IllegalStateException("Önce bir sunucu ekle")
            VpnConfig.parse(s.raw)?.outbound ?: throw IllegalStateException("Sunucu ayarı okunamadı")
        }
    }

    // ---------------------------------------------------------------- the core

    /** Everything that touches the native library, so a phone without it only loses the VPN. */
    private object Core {
        @Volatile
        private var controller: Any? = null
        private var env = false

        val supported: Boolean by lazy {
            try {
                libv2ray.Libv2ray.checkVersionX()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "no xray", t)
                false
            }
        }

        fun version(): String = try {
            libv2ray.Libv2ray.checkVersionX()
        } catch (t: Throwable) {
            ""
        }

        private fun env(context: Context) {
            if (env) return
            val dir = java.io.File(context.filesDir, "xray").apply { mkdirs() }
            libv2ray.Libv2ray.initCoreEnv(dir.absolutePath, "")
            env = true
        }

        fun start(context: Context, config: String) {
            env(context)
            val c = (controller as? libv2ray.CoreController) ?: libv2ray.Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long {
                    Log.i(TAG, "xray: $p1")
                    return 0
                }
            }).also { controller = it }
            c.startLoop(config, 0)
        }

        fun stop() {
            try {
                (controller as? libv2ray.CoreController)?.stopLoop()
            } catch (t: Throwable) {
                Log.w(TAG, "stop", t)
            }
        }

        fun delay(context: Context, config: String): Long {
            env(context)
            return libv2ray.Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
        }
    }

    fun supported(): Boolean = Core.supported
    fun coreVersion(): String = Core.version()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitPort(p: Int) {
        val until = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < until) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 300); return }
            } catch (t: Throwable) {
                Thread.sleep(80)
            }
        }
    }

    private fun appBytes(): Long = try {
        val uid = Process.myUid()
        (TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)).coerceAtLeast(0)
    } catch (t: Throwable) {
        0
    }

    fun sessionBytes(): Long = if (running) (appBytes() - bytesAtStart).coerceAtLeast(0) else 0

    /** Null when it runs, otherwise why not. */
    @Synchronized
    fun start(context: Context): String? {
        if (running) return null
        val ctx = context.applicationContext
        if (!available(ctx)) return "VPN kapalı (admin)"
        if (!Core.supported) return "Bu cihaz VPN çekirdeğini desteklemiyor"
        readSettings(ctx)
        return try {
            val out = outbound(ctx)
            val p = freePort()
            Core.start(ctx, VpnConfig.full(out, p))
            port = p
            waitPort(p)
            running = true
            since = System.currentTimeMillis()
            bytesAtStart = appBytes()
            lastError = null
            dropConnections()
            onState?.invoke()
            null
        } catch (t: Throwable) {
            Log.w(TAG, "start", t)
            Core.stop()
            running = false
            (t.message ?: t.javaClass.simpleName).also { lastError = it }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        Core.stop()
        dropConnections()
        onState?.invoke()
    }

    fun restart(context: Context): String? {
        if (!running) return null
        stop()
        return start(context)
    }

    /** Open connections were made without (or with) the tunnel; the next requests make new ones. */
    private fun dropConnections() {
        try {
            com.lagradost.cloudstream3.app.baseClient.connectionPool.evictAll()
        } catch (t: Throwable) {
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        readSettings(ctx)
        if (available(ctx) && flag(ctx, "auto", false)) scope.launch { start(ctx) }
        if (!available(ctx) && running) stop()
    }

    // ---------------------------------------------------------------- which requests go through

    private fun bare(h: String) = h.lowercase().removePrefix("www.")

    private fun troubledHosts(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - troubledAt > 30_000) {
            troubledAt = now
            troubled = Domains.troubledHosts().map { bare(it) }.toSet()
        }
        return troubled
    }

    private fun inSmartSet(host: String): Boolean {
        val h = bare(host)
        fun match(set: Set<String>) = set.any { h == it || h.endsWith(".$it") }
        return match(learned) || match(troubledHosts())
    }

    private fun isLocal(host: String) = host == "127.0.0.1" || host == "localhost" || host == "::1"

    private fun proxies(): List<Proxy> {
        val socks = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        return if (failOpen) listOf(socks, Proxy.NO_PROXY) else listOf(socks)
    }

    private fun tunnels(host: String?, player: Boolean): Boolean {
        if (!running || host == null || isLocal(host)) return false
        return when (scopeNow) {
            SCOPE_ALL -> true
            SCOPE_SITES -> !player
            else -> inSmartSet(host)
        }
    }

    private class Selector(val player: Boolean) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            if (tunnels(uri?.host, player)) proxies() else listOf(Proxy.NO_PROXY)

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    /** For the app's requests (buildDefaultClient). */
    val selector: ProxySelector = Selector(false)

    /** For the player: in "sites" mode the videos go straight. */
    val playerSelector: ProxySelector = Selector(true)

    fun forPlayer(client: OkHttpClient): OkHttpClient = client.newBuilder().proxySelector(playerSelector).build()

    /** Cronet cannot use the tunnel; the player takes OkHttp for a video that has to go through it. */
    fun tunnelsVideo(url: String?): Boolean = try {
        tunnels(url?.let { URI(it).host }, true)
    } catch (t: Throwable) {
        false
    }

    private fun looksBlocked(e: IOException): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
        is SSLException -> true
        is SocketException -> true
        is InterruptedIOException -> e.message?.contains("connect", true) == true || e.message?.contains("timeout", true) == true
        else -> false
    }

    /**
     * A site that does not open: in "only blocked sites" its host is learned and tried again
     * through the tunnel; with "open by itself" the VPN is started first.
     */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val retry = try {
                retryThroughTunnel(request, e)
            } catch (t: Throwable) {
                false
            }
            if (!retry || chain.call().isCanceled()) throw e
            chain.proceed(request)
        }
    }

    private fun retryThroughTunnel(request: Request, e: IOException): Boolean {
        val ctx = appContext ?: return false
        val host = request.url.host
        if (isLocal(host) || !looksBlocked(e) || !available(ctx)) return false
        if (running && tunnels(host, false)) return false // it already went through the tunnel
        if (!running) {
            if (!autoOpen || !Core.supported) return false
            if (start(ctx) != null) return false
        }
        if (scopeNow == SCOPE_SMART && learned.add(bare(host))) saveLearned(ctx)
        Log.i(TAG, "through the tunnel now: $host")
        return tunnels(host, false)
    }

    // ---------------------------------------------------------------- tests

    private fun trace(client: OkHttpClient): JSONObject {
        val req = Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { res ->
            val o = JSONObject()
            (res.body?.string() ?: "").lines().forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) o.put(l.substring(0, i), l.substring(i + 1))
            }
            return o
        }
    }

    /** {"direct": {ip, loc}, "vpn": {ip, loc, colo, warp}} */
    fun test(): JSONObject {
        val base = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val out = JSONObject()
        try {
            out.put("direct", trace(base.newBuilder().proxy(Proxy.NO_PROXY).build()))
        } catch (t: Throwable) {
            out.put("direct", JSONObject().put("error", t.message ?: "hata"))
        }
        if (running) {
            try {
                out.put("vpn", trace(base.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).build()))
            } catch (t: Throwable) {
                out.put("vpn", JSONObject().put("error", t.message ?: "hata"))
            }
        }
        return out
    }

    /** Real delay through a server, in ms; -1 when it does not work. */
    fun delay(context: Context, mode: String, serverId: String?): Long = try {
        if (!Core.supported) -1 else Core.delay(context, VpnConfig.full(outbound(context, mode, serverId), freePort()))
    } catch (t: Throwable) {
        Log.w(TAG, "delay", t)
        -1
    }
}
