package com.lagradost.cloudstream3.fork

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.AssetPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The addresses of the sites of the plugins. When a site moves to a new domain, the new one can be
 * given here (Plus > Domain) or from outside ("domains" in Cloudstreamplus.json); it goes into the
 * plugin and, when asked, every request to the old domain goes to the new one, so addresses the
 * plugin wrote into its code work too. Order: the user's own > the admin's > the plugin's.
 */
object Domains {
    private const val TAG = "ForkDomains"
    private const val PREFS = "fork_domains"
    const val PREF_AUTO = "fork_domains_auto"
    const val PREF_REWRITE = "fork_domains_rewrite"
    const val PREF_AUTOAPPLY = "fork_domains_autoapply"
    private const val CHECK_EVERY = 12L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** provider name -> the address the plugin came with */
    private val defaults = ConcurrentHashMap<String, String>()

    /** provider name -> the address that was put into it last */
    private val applied = ConcurrentHashMap<String, String>()

    /** old host -> new address (scheme + host), for the requests */
    @Volatile
    private var moves: Map<String, String> = emptyMap()

    @Volatile
    private var rewriteOn = true

    /** provider name -> what the last check said */
    private val results = ConcurrentHashMap<String, Check>()

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.DOMAINS, context)

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    // ---------------------------------------------------------------- addresses

    private fun clean(url: String?): String? {
        var u = url?.trim()?.trimEnd('/') ?: return null
        if (u.isEmpty() || u == "NONE") return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    fun hostOf(url: String?): String? = try {
        url?.let { java.net.URI(clean(it)).host?.lowercase()?.removePrefix("www.") }
    } catch (t: Throwable) {
        null
    }

    private fun own(context: Context): JSONObject = try {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("own", "{}") ?: "{}")
    } catch (t: Throwable) {
        JSONObject()
    }

    fun ownOf(context: Context, name: String): String? = clean(own(context).optString(name, ""))

    fun setOwn(context: Context, name: String, url: String?) {
        val all = own(context)
        val value = clean(url)
        if (value == null) all.remove(name) else all.put(name, value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("own", all.toString()).apply()
        results.remove(name)
        apply(context)
    }

    /** "domains": { "Provider name" or "old.host": "https://new.host" } */
    fun adminOf(context: Context, name: String, default: String?): String? {
        val node = ForkFeatures.general(context)?.get("domains") ?: return null
        if (!node.isObject) return null
        node.get(name)?.asText()?.let { clean(it) }?.let { return it }
        val host = hostOf(default) ?: return null
        val it = node.fields()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (hostOf(k) == host) return clean(v.asText())
        }
        return null
    }

    private fun adminCount(context: Context): Int =
        ForkFeatures.general(context)?.get("domains")?.takeIf { it.isObject }?.size() ?: 0

    private fun sites(): List<MainAPI> = try {
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.filter { api ->
                api.sourcePlugin != null && !Iptv.isIptv(api) && api.mainUrl.startsWith("http")
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun defaultOf(api: MainAPI): String {
        val now = api.mainUrl
        // a new copy of the plugin comes with its own address again
        if (applied[api.name] != now || defaults[api.name] == null) defaults[api.name] = now
        return defaults[api.name] ?: now
    }

    fun currentOf(context: Context, api: MainAPI): String {
        val def = defaultOf(api)
        return ownOf(context, api.name) ?: adminOf(context, api.name, def) ?: def
    }

    /** Puts the chosen addresses into the plugins and makes the list of moved hosts. */
    fun apply(context: Context) {
        val on = available(context)
        rewriteOn = prefs(context).getBoolean(PREF_REWRITE, true)
        val moved = HashMap<String, String>()
        for (api in sites()) {
            val def = defaultOf(api)
            val target = if (on) currentOf(context, api) else def
            if (api.mainUrl != target) {
                try {
                    api.mainUrl = target
                } catch (t: Throwable) {
                    Log.w(TAG, "could not set ${api.name}", t)
                }
            }
            applied[api.name] = api.mainUrl
            val newHost = hostOf(target) ?: continue
            if (on) listOfNotNull(def, adminOf(context, api.name, def)).mapNotNull { hostOf(it) }.forEach { old ->
                if (old != newHost) moved[old] = target
            }
        }
        moves = moved
    }

    // ---------------------------------------------------------------- requests to the old domain

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val all = moves
        if (!rewriteOn || all.isEmpty()) return@Interceptor chain.proceed(request)
        val host = request.url.host.lowercase().removePrefix("www.")
        val target = all[host] ?: return@Interceptor chain.proceed(request)
        val newUrl = try {
            val t = java.net.URI(target)
            request.url.newBuilder().scheme(t.scheme ?: "https").host(t.host).build()
        } catch (t: Throwable) {
            null
        } ?: return@Interceptor chain.proceed(request)
        val b = request.newBuilder().url(newUrl)
        listOf("Referer", "Origin").forEach { h ->
            request.header(h)?.let { v ->
                val vh = hostOf(v)
                if (vh != null && all[vh] != null) b.header(h, v.replace(Regex("^https?://(www\\.)?[^/]+"), all[vh]!!))
            }
        }
        chain.proceed(b.build())
    }

    // ---------------------------------------------------------------- checking

    class Check(val st: String, val next: String?, val code: Int, val ms: Long, val msg: String) {
        fun json(): JSONObject = JSONObject().put("st", st).put("next", next ?: JSONObject.NULL)
            .put("code", code).put("ms", ms).put("msg", msg)
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val metaRefresh = Regex("""http-equiv=["']?refresh["']?[^>]*url=([^"'>\s]+)""", RegexOption.IGNORE_CASE)

    fun check(url: String): Check {
        val start = System.currentTimeMillis()
        val target = clean(url) ?: return Check("bad", null, 0, 0, "Adres boş")
        val wanted = hostOf(target)
        return try {
            val req = Request.Builder().url(target)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .build()
            client.newCall(req).execute().use { res: Response ->
                val ms = System.currentTimeMillis() - start
                val finalHost = res.request.url.host.lowercase().removePrefix("www.")
                val finalBase = res.request.url.scheme + "://" + res.request.url.host
                val cloudflare = res.header("server")?.contains("cloudflare", true) == true || res.header("cf-ray") != null
                var body = ""
                if (res.code < 400) body = try { res.peekBody(64 * 1024).string() } catch (t: Throwable) { "" }
                val meta = metaRefresh.find(body)?.groupValues?.get(1)?.let { hostOf(it)?.let { h -> if (h != wanted) "https://$h" else null } }
                when {
                    finalHost != wanted && res.code < 500 -> Check("warn", finalBase, res.code, ms, "Yönlendiriyor")
                    meta != null -> Check("warn", meta, res.code, ms, "Sayfa yeni adrese gönderiyor")
                    res.code < 400 -> Check("ok", null, res.code, ms, "Çalışıyor")
                    cloudflare && (res.code == 403 || res.code == 503) -> Check("ok", null, res.code, ms, "Cloudflare korumalı, açık")
                    else -> Check("bad", null, res.code, ms, "HTTP ${res.code}")
                }
            }
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - start
            val why = when (t) {
                is java.net.UnknownHostException -> "Domain bulunamadı"
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Zaman aşımı"
                is javax.net.ssl.SSLException -> "SSL hatası"
                is java.net.ConnectException -> "Bağlanılamadı"
                else -> t.javaClass.simpleName
            }
            Check("bad", null, 0, ms, why)
        }
    }

    private suspend fun checkAll(context: Context, each: (done: Int, total: Int, name: String, c: Check) -> Unit): List<Pair<MainAPI, Check>> {
        val list = sites()
        val gate = Semaphore(6)
        var done = 0
        return kotlinx.coroutines.coroutineScope {
            list.map { api ->
                async {
                    gate.withPermit {
                        val c = check(currentOf(context, api))
                        results[api.name] = c
                        synchronized(this@Domains) { done++ }
                        each(done, list.size, api.name, c)
                        api to c
                    }
                }
            }.awaitAll()
        }
    }

    // ---------------------------------------------------------------- start

    private var hooked = false

    fun init(activity: FragmentActivity) {
        val context = activity.applicationContext
        if (!hooked) {
            hooked = true
            MainActivity.afterPluginsLoadedEvent += { _: Boolean ->
                try {
                    apply(context)
                } catch (t: Throwable) {
                    Log.w(TAG, "apply failed", t)
                }
                autoCheck(context)
            }
        }
        apply(context)
    }

    private fun autoCheck(context: Context) {
        if (!available(context) || !prefs(context).getBoolean(PREF_AUTO, true)) return
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - store.getLong("checked", 0L) < CHECK_EVERY || sites().isEmpty()) return
        store.edit().putLong("checked", now).apply()
        scope.launch {
            val found = checkAll(context) { _, _, _, _ -> }.filter { it.second.next != null }
            if (found.isEmpty()) return@launch
            val autoApply = prefs(context).getBoolean(PREF_AUTOAPPLY, false)
            if (autoApply) found.forEach { (api, c) -> setOwn(context, api.name, c.next) }
            withContext(Dispatchers.Main) {
                showToast(
                    context.getString(if (autoApply) R.string.domains_auto_applied else R.string.domains_found, found.size),
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    // ---------------------------------------------------------------- the page in Plus

    private fun repoNames(): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            (RepositoryManager.getRepositories() + RepositoryManager.PREBUILT_REPOSITORIES).forEach {
                map[PluginManager.getPluginSanitizedFileName(it.url)] = it.name
            }
        } catch (t: Throwable) {
        }
        return map
    }

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private fun js(script: String) = handle?.runScript(script)

        @JavascriptInterface
        fun data(): String {
            val names = repoNames()
            val groups = LinkedHashMap<String, JSONArray>()
            sites().sortedBy { it.name.lowercase() }.forEach { api ->
                val folder = api.sourcePlugin?.let { File(it).parentFile?.name }
                val repo = names[folder] ?: if (folder == null) "Diğer" else activity.getString(R.string.domains_local)
                val def = defaultOf(api)
                val r = results[api.name]
                groups.getOrPut(repo) { JSONArray() }.put(
                    JSONObject().put("n", api.name).put("d", def)
                        .put("adm", adminOf(activity, api.name, def) ?: JSONObject.NULL)
                        .put("own", ownOf(activity, api.name) ?: JSONObject.NULL)
                        .put("cur", currentOf(activity, api))
                        .put("st", r?.st ?: JSONObject.NULL).put("next", r?.next ?: JSONObject.NULL)
                )
            }
            val repos = JSONArray()
            groups.entries.sortedBy { it.key.lowercase() }.forEach { (k, v) -> repos.put(JSONObject().put("name", k).put("sites", v)) }
            val p = prefs(activity)
            return JSONObject().put("repos", repos)
                .put("settings", JSONObject().put("auto", p.getBoolean(PREF_AUTO, true))
                    .put("rewrite", p.getBoolean(PREF_REWRITE, true)).put("autoapply", p.getBoolean(PREF_AUTOAPPLY, false)))
                .put("admin", adminCount(activity)).toString()
        }

        @JavascriptInterface
        fun set(key: String?, on: Boolean) {
            val pref = when (key) {
                "auto" -> PREF_AUTO
                "rewrite" -> PREF_REWRITE
                "autoapply" -> PREF_AUTOAPPLY
                else -> return
            }
            prefs(activity).edit().putBoolean(pref, on).apply()
            apply(activity)
        }

        @JavascriptInterface
        fun save(name: String?, url: String?) {
            name ?: return
            scope.launch {
                val api = sites().firstOrNull { it.name == name }
                // the address the plugin or admin gives is not kept as the user's own
                val value = clean(url)
                val base = api?.let { a -> defaultOf(a).let { d -> adminOf(activity, name, d) ?: d } }
                setOwn(activity, name, if (value != null && value == base) null else value)
                withContext(Dispatchers.Main) { MainActivity.afterPluginsLoadedEvent.invoke(false) }
            }
        }

        @JavascriptInterface
        fun reset(name: String?) = save(name, null)

        @JavascriptInterface
        fun check(token: Int, url: String?) {
            scope.launch {
                val c = Domains.check(url ?: "")
                js("window.onDomainResult && window.onDomainResult($token, ${JSONObject.quote(c.json().toString())})")
            }
        }

        @JavascriptInterface
        fun checkAll() {
            scope.launch {
                checkAll(activity) { done, total, name, c ->
                    js("window.onDomainProgress && window.onDomainProgress($done, $total, ${JSONObject.quote(name)}, ${JSONObject.quote(c.json().toString())})")
                }
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("checked", System.currentTimeMillis()).apply()
                js("window.onDomainDone && window.onDomainDone()")
            }
        }
    }

    fun show(activity: FragmentActivity) {
        val bridge = Bridge(activity)
        bridge.handle = AssetPage.show(activity, "domains.html", bridges = mapOf("Domains" to bridge))
    }
}
