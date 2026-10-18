package com.lagradost.cloudstream3.fork.scraper

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.ForkFeatures
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.ui.settings.AssetPage
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plus > Site Kazıyıcı: sites described with JSON become sources of the home page. The
 * descriptions are kept in the preferences; each one can be switched off, edited or tested.
 */
object Scrapers {
    private const val TAG = "ForkScrapers"
    private const val PREFS = "fork_scrapers"
    const val TAG_PREFIX = "scraper:"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.SCRAPER, context)

    class Site(val id: String, val json: String, val on: Boolean, val st: String, val info: String) {
        val cfg: JSONObject get() = try { JSONObject(json) } catch (t: Throwable) { JSONObject() }
        fun toJson(): JSONObject = JSONObject().put("id", id).put("json", json).put("on", on).put("st", st).put("info", info)
    }

    fun sites(context: Context): List<Site> = try {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Site(it.optString("id"), it.optString("json"), it.optBoolean("on", true), it.optString("st", "new"), it.optString("info"))
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun save(context: Context, list: List<Site>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun update(context: Context, id: String, change: (Site) -> Site) =
        save(context, sites(context).map { if (it.id == id) change(it) else it })

    fun isScraper(sourcePlugin: String?) = sourcePlugin?.startsWith(TAG_PREFIX) == true

    /** null when the description can be used, otherwise why not */
    fun problem(cfg: JSONObject): String? {
        if (cfg.optString("name").isBlank()) return "\"name\" yok"
        if (!cfg.optString("baseUrl").startsWith("http")) return "\"baseUrl\" http(s):// ile başlamalı"
        val api = cfg.optJSONObject("api")
        if (api != null) {
            if (!api.optString("base").startsWith("http")) return "\"api.base\" http(s):// ile başlamalı"
            if (api.optString("streamsPath").isBlank()) return "\"api.streamsPath\" yok"
        } else {
            if (cfg.optJSONObject("search")?.optString("item").isNullOrBlank()) return "\"search.item\" yok"
        }
        return null
    }

    @Synchronized
    fun register(context: Context) {
        APIHolder.apis.filter { isScraper(it.sourcePlugin) }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { isScraper(it.sourcePlugin) } }
        if (available(context)) {
            sites(context).filter { it.on }.forEach { s ->
                val cfg = s.cfg
                if (problem(cfg) != null) return@forEach
                try {
                    val p = ScraperProvider(s.id, cfg)
                    p.sourcePlugin = TAG_PREFIX + s.id
                    APIHolder.allProviders.add(p)
                    APIHolder.addPluginMapping(p)
                } catch (t: Throwable) {
                    Log.w(TAG, "could not make ${s.id}", t)
                }
            }
        }
        try {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        } catch (t: Throwable) {
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        scope.launch { register(ctx) }
    }

    // ---------------------------------------------------------------- the test

    private fun step(n: Int, title: String, st: String, url: String?, text: String, posters: JSONArray? = null) =
        JSONObject().put("n", n).put("title", title).put("st", st).put("url", url ?: "").put("text", text)
            .put("posters", posters ?: JSONArray())

    private fun cards(list: List<com.lagradost.cloudstream3.SearchResponse>) =
        JSONArray(list.take(8).map { JSONObject().put("name", it.name).put("poster", it.posterUrl ?: "") })

    private fun why(t: Throwable): String = (t.message ?: t.javaClass.simpleName).take(160)

    /** Runs every step against the site and tells the page after each one. Returns "ok", "warn" or "bad". */
    suspend fun test(cfg: JSONObject, query: String, emit: (JSONObject) -> Unit): Pair<String, String> {
        val p = ScraperProvider("test", cfg)
        val results = ArrayList<String>()
        val notes = ArrayList<String>()
        fun put(s: JSONObject) {
            results.add(s.optString("st")); emit(s)
        }

        // 1 home
        var firstCard: com.lagradost.cloudstream3.SearchResponse? = null
        try {
            val cat = p.mainPage.first()
            val home = withTimeout(25_000) { p.getMainPage(1, com.lagradost.cloudstream3.MainPageRequest(cat.name, cat.data, false)) }
            val list = home?.items?.firstOrNull()?.list.orEmpty()
            firstCard = list.firstOrNull()
            put(step(1, "Ana sayfa · ${cat.name}", if (list.isEmpty()) "warn" else "ok", null,
                if (list.isEmpty()) "Öğe bulunamadı (katalog seçicisini ya da yolunu kontrol et)" else "${p.mainPage.size} katalog · ilkinde ${list.size} öğe", cards(list)))
            if (list.isNotEmpty()) notes.add("ana sayfa ${list.size}")
        } catch (t: Throwable) {
            put(step(1, "Ana sayfa", "bad", null, why(t)))
        }

        // 2 search
        try {
            val list = withTimeout(25_000) { p.search(query) }
            if (list.isNotEmpty()) firstCard = list.first()
            put(step(2, "Arama: $query", if (list.isEmpty()) "warn" else "ok", null,
                if (list.isEmpty()) "Sonuç yok (arama adresini ya da öğe seçicisini kontrol et)" else "${list.size} sonuç · ilk: ${list.first().name}", cards(list)))
            notes.add("arama ${list.size}")
        } catch (t: Throwable) {
            put(step(2, "Arama: $query", "bad", null, why(t)))
        }

        val card = firstCard
        if (card == null) {
            put(step(3, "Detay", "bad", null, "Açılacak bir içerik bulunamadı"))
            return finish(results, notes)
        }

        // 3 detail + 4 episodes
        var data: String? = null
        try {
            val res = withTimeout(30_000) { p.load(card.url) }
            if (res == null) {
                put(step(3, "Detay", "bad", card.url, "Sayfa okunamadı"))
            } else {
                put(step(3, "Detay", "ok", card.url, "${res.name}" + (if (res.posterUrl != null) " · afiş var" else " · afiş yok") +
                    (if (!res.plot.isNullOrBlank()) " · özet var" else ""), JSONArray().put(JSONObject().put("name", res.name).put("poster", res.posterUrl ?: ""))))
                when (res) {
                    is TvSeriesLoadResponse -> {
                        val eps = res.episodes
                        var hint = ""
                        if (eps.isEmpty() && !p.isApi) {
                            try {
                                val h = p.episodeHints(p.docOf(card.url))
                                if (h.isNotEmpty()) hint = " · benzerler: " + h.joinToString(", ")
                            } catch (t: Throwable) {
                            }
                        }
                        put(step(4, "Bölümler", if (eps.isEmpty()) "warn" else "ok", null,
                            if (eps.isEmpty()) "Bölüm bulunamadı$hint" else "${eps.size} bölüm · ilk: ${eps.first().name ?: ("Bölüm " + (eps.first().episode ?: 1))}"))
                        data = eps.firstOrNull()?.data
                        notes.add("bölüm ${eps.size}")
                    }
                    is MovieLoadResponse -> {
                        put(step(4, "Bölümler", "ok", null, "Film · bölüm yok"))
                        data = res.dataUrl
                    }
                    else -> put(step(4, "Bölümler", "warn", null, "Bilinmeyen tür"))
                }
            }
        } catch (t: Throwable) {
            put(step(3, "Detay", "bad", card.url, why(t)))
        }

        // 5 links
        val d = data
        if (d == null) {
            put(step(5, "Kaynaklar", "bad", null, "Oynatılacak bölüm yok"))
        } else {
            try {
                val links = ArrayList<com.lagradost.cloudstream3.utils.ExtractorLink>()
                var subs = 0
                withTimeout(45_000) { p.loadLinks(d, false, { subs++ }, { links.add(it) }) }
                var hidden = ""
                if (links.isEmpty() && !p.isApi) {
                    try {
                        val decoded = p.scriptLinksOf(d)
                        if (decoded.isNotEmpty()) {
                            val parts = decoded.map { it.second }.distinctBy { it.substringAfter("//").substringBefore('/') }.take(4).map { l ->
                                val host = l.substringAfter("//").substringBefore('/')
                                val ex = p.extractorsFor(l).firstOrNull()
                                if (ex != null) "$host → ${ex.name} çözücüsü var ama video vermedi" else "$host → çözücü yok"
                            }
                            hidden = " · şifreli kaynaktan ${decoded.size} link çözüldü: " + parts.joinToString("; ") +
                                (if (parts.any { it.endsWith("çözücü yok") }) ". Bu sunucunun çözücüsünü içeren eklentiyi (ör. sitenin .cs3'ü) Dosyadan eklentiler'den yükle" else "")
                        }
                    } catch (t: Throwable) {
                    }
                }
                val text = if (links.isEmpty()) "Video bulunamadı (video / m3u8 / iframe / script kısmını kontrol et)$hidden" else
                    links.take(4).joinToString(" · ") { (if (it.type == ExtractorLinkType.M3U8) "m3u8 " else "") + it.name } +
                        (if (links.size > 4) " · +${links.size - 4}" else "") + (if (subs > 0) " · $subs altyazı" else "")
                put(step(5, "Kaynaklar", if (links.isEmpty()) "warn" else "ok", null, text))
                notes.add("kaynak ${links.size}")
            } catch (t: Throwable) {
                put(step(5, "Kaynaklar", "bad", null, why(t)))
            }
        }
        return finish(results, notes)
    }

    private fun finish(results: List<String>, notes: List<String>): Pair<String, String> {
        val st = when {
            results.contains("bad") -> "bad"
            results.contains("warn") -> "warn"
            else -> "ok"
        }
        return st to ("son test: " + notes.joinToString(", "))
    }

    // ---------------------------------------------------------------- the page

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private fun js(script: String) = handle?.runScript(script)

        private fun answer(token: Int, json: JSONObject) =
            js("window.onScrResult && window.onScrResult($token, ${JSONObject.quote(json.toString())})")

        @JavascriptInterface
        fun list(): String = JSONArray(Scrapers.sites(activity).map { s ->
            val c = s.cfg
            JSONObject().put("id", s.id).put("name", c.optString("name")).put("kind", if (c.has("api")) "api" else "html")
                .put("base", c.optString("baseUrl")).put("on", s.on).put("st", s.st).put("info", s.info)
                .put("problem", problem(c) ?: JSONObject.NULL)
        }).toString()

        @JavascriptInterface
        fun get(id: String?): String = Scrapers.sites(activity).firstOrNull { it.id == id }?.json ?: ""

        @JavascriptInterface
        fun save(token: Int, id: String?, text: String?) {
            val cfg = try {
                JSONObject(text ?: "")
            } catch (t: Throwable) {
                answer(token, JSONObject().put("ok", false).put("message", "JSON hatalı: " + (t.message ?: "")))
                return
            }
            problem(cfg)?.let {
                answer(token, JSONObject().put("ok", false).put("message", it))
                return
            }
            val pretty = cfg.toString(2)
            val list = Scrapers.sites(activity)
            val newId = id?.takeIf { i -> list.any { it.id == i } } ?: (System.currentTimeMillis().toString(36))
            if (list.any { it.id == newId }) {
                update(activity, newId) { Site(it.id, pretty, it.on, "new", "henüz test edilmedi") }
            } else {
                Scrapers.save(activity, list + Site(newId, pretty, true, "new", "henüz test edilmedi"))
            }
            scope.launch { register(activity) }
            answer(token, JSONObject().put("ok", true).put("id", newId).put("message", cfg.optString("name") + " kaydedildi"))
        }

        @JavascriptInterface
        fun remove(id: String?) {
            id ?: return
            Scrapers.save(activity, Scrapers.sites(activity).filter { it.id != id })
            scope.launch { register(activity) }
        }

        @JavascriptInterface
        fun setOn(id: String?, on: Boolean) {
            id ?: return
            update(activity, id) { Site(it.id, it.json, on, it.st, it.info) }
            scope.launch { register(activity) }
        }

        @JavascriptInterface
        fun test(token: Int, id: String?, text: String?, query: String?) {
            val cfg = try {
                JSONObject(text ?: "")
            } catch (t: Throwable) {
                answer(token, JSONObject().put("done", true).put("st", "bad").put("message", "JSON hatalı"))
                return
            }
            scope.launch {
                val (st, info) = try {
                    Scrapers.test(cfg, query?.ifBlank { null } ?: "a") { step ->
                        js("window.onScrStep && window.onScrStep($token, ${JSONObject.quote(step.toString())})")
                    }
                } catch (t: Throwable) {
                    "bad" to (t.message ?: "hata")
                }
                if (id != null && Scrapers.sites(activity).any { it.id == id }) {
                    update(activity, id) { Site(it.id, it.json, it.on, st, info) }
                }
                answer(token, JSONObject().put("done", true).put("st", st).put("info", info))
            }
        }

        @JavascriptInterface
        fun fetch(token: Int, url: String?) {
            scope.launch {
                val out = JSONObject()
                try {
                    val text = app.get(url ?: "").text
                    JSONObject(text.trim())
                    out.put("ok", true).put("text", text)
                } catch (t: Throwable) {
                    out.put("ok", false).put("message", "Link açılamadı ya da JSON değil")
                }
                answer(token, out)
            }
        }
    }

    fun show(activity: FragmentActivity) {
        val bridge = Bridge(activity)
        bridge.handle = AssetPage.show(activity, "scraper.html", bridges = mapOf("Scraper" to bridge))
    }

    @Suppress("unused")
    private suspend fun main(block: suspend () -> Unit) = withContext(Dispatchers.Main) { block() }
}
