package com.lagradost.cloudstream3.fork.scraper

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A source made from a JSON description (Plus > Site Kazıyıcı). Two kinds:
 *  - HTML: pages are read with CSS selectors ("a@href" = attribute, "h1" = text, "@src" = the item itself)
 *  - API:  the site's JSON service ("api" object; paths with {id}, {ep}, {lang}; fields with "a.b.c")
 */
class ScraperProvider(val id: String, val cfg: JSONObject) : MainAPI() {
    override var name = cfg.optString("name").ifBlank { "Kazıyıcı" }
    override var mainUrl = cfg.optString("baseUrl").trimEnd('/')
    override var lang = cfg.optString("lang").ifBlank { "tr" }
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val api: JSONObject? = cfg.optJSONObject("api")
    val isApi get() = api != null

    override val supportedTypes: Set<TvType> by lazy {
        val cats = (api ?: cfg).optJSONArray("catalogs")
        val types = (0 until (cats?.length() ?: 0)).mapNotNull { cats?.optJSONObject(it)?.optString("type") }
            .map { typeOf(it) }.toMutableSet()
        cfg.optJSONObject("detail")?.optString("type")?.takeIf { it.isNotBlank() }?.let { types.add(typeOf(it)) }
        types.ifEmpty { mutableSetOf(TvType.Movie, TvType.TvSeries) }
    }

    override val mainPage: List<MainPageData> by lazy {
        val cats = (api ?: cfg).optJSONArray("catalogs")
        val list = (0 until (cats?.length() ?: 0)).mapNotNull { cats?.optJSONObject(it) }
            .map { MainPageData(it.optString("name").ifBlank { it.optString("id") }, it.toString()) }
        list.ifEmpty { listOf(MainPageData(name, JSONObject().put("id", "home").toString())) }
    }

    // ---------------------------------------------------------------- small helpers

    private fun hdrs(): Map<String, String> {
        val h = HashMap<String, String>()
        (api?.optJSONObject("headers") ?: cfg.optJSONObject("headers"))?.let { o -> o.keys().forEach { k -> h[k] = o.optString(k) } }
        return h
    }

    private fun typeOf(s: String?): TvType = when (s?.lowercase()) {
        "movie", "film" -> TvType.Movie
        "anime" -> TvType.Anime
        "live", "tv" -> TvType.Live
        "documentary" -> TvType.Documentary
        "cartoon" -> TvType.Cartoon
        else -> TvType.TvSeries
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun abs(u: String?): String? {
        val v = u?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            v.startsWith("http") -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") -> mainUrl + v
            else -> "$mainUrl/$v"
        }
    }

    private suspend fun getText(url: String, referer: String? = null): String =
        app.get(url, headers = hdrs(), referer = referer ?: "$mainUrl/").text

    private suspend fun getDoc(url: String): Document = Jsoup.parse(getText(url), url)

    // ---------------------------------------------------------------- HTML: selectors

    private val attrLike = Regex("^(data-[\\w-]+|href|src|title|alt|content|value|poster)$")

    private fun attrOf(el: Element, attr: String): String? {
        if (attr == "src" || attr == "data-src") {
            for (a in listOf("data-src", "data-lazy-src", "data-original", "src")) {
                el.attr(a).takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { return it }
            }
            el.attr("srcset").takeIf { it.isNotBlank() }?.let { return it.substringBefore(' ') }
        }
        return el.attr(attr).takeIf { it.isNotBlank() }
    }

    /** "css@attr", "@attr", "data-ep" (attribute), "css" (text) */
    fun pick(el: Element, selector: String?): String? {
        val s = selector?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            when {
                s.startsWith("@") -> attrOf(el, s.substring(1))
                s.lastIndexOf('@') > 0 -> {
                    val at = s.lastIndexOf('@')
                    el.selectFirst(s.substring(0, at))?.let { attrOf(it, s.substring(at + 1)) }
                }
                attrLike.matches(s) && el.hasAttr(s) -> el.attr(s)
                attrLike.matches(s) -> el.selectFirst("[$s]")?.attr(s) ?: el.selectFirst(s)?.text()
                else -> el.selectFirst(s)?.text()?.trim()
            }?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun htmlCards(doc: Document, item: String?, title: String?, href: String?, poster: String?, type: TvType): List<SearchResponse> {
        val items = try {
            doc.select(item ?: return emptyList())
        } catch (t: Throwable) {
            return emptyList()
        }
        return items.mapNotNull { el ->
            val link = abs(pick(el, href ?: "a@href") ?: return@mapNotNull null) ?: return@mapNotNull null
            val t = pick(el, title ?: "a@title") ?: pick(el, "img@alt") ?: return@mapNotNull null
            newMovieSearchResponse(t, link, type, fix = false) { posterUrl = abs(pick(el, poster ?: "img@src")) }
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- API: fields

    fun path(obj: Any?, p: String?): Any? {
        if (p.isNullOrBlank()) return obj
        var cur: Any? = obj
        for (part in p.split('.')) {
            cur = when (cur) {
                is JSONObject -> if (cur.has(part)) cur.opt(part) else null
                is JSONArray -> part.toIntOrNull()?.let { cur.opt(it) }
                else -> null
            }
            if (cur == null || cur == JSONObject.NULL) return null
        }
        return cur
    }

    /** a value as text; for objects like {"english": .., "romaji": ..} the first useful one */
    private fun textOf(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v.takeIf { it.isNotBlank() }
        is Number, is Boolean -> v.toString()
        is JSONObject -> listOf("english", "en", "userPreferred", "romaji", "name", "title", "url", "large", "medium", "default")
            .firstNotNullOfOrNull { k -> textOf(v.opt(k)) }
        is JSONArray -> textOf(v.opt(0))
        else -> v.toString()
    }

    private fun field(o: Any?, custom: String?, vararg defaults: String): String? {
        custom?.takeIf { it.isNotBlank() }?.let { c -> textOf(path(o, c))?.let { return it } }
        for (d in defaults) textOf(path(o, d))?.let { return it }
        return null
    }

    private fun arrayIn(o: Any?, vararg names: String?): JSONArray? {
        for (n in names) {
            if (n.isNullOrBlank()) continue
            when (val v = path(o, n)) {
                is JSONArray -> return v
                is JSONObject -> listOf("results", "items", "data", "list", "animes").firstNotNullOfOrNull { v.optJSONArray(it) }?.let { return it }
            }
        }
        return when (o) {
            is JSONArray -> o
            is JSONObject -> listOf("results", "items", "data", "list").firstNotNullOfOrNull { o.optJSONArray(it) }
            else -> null
        }
    }

    private fun json(text: String): Any = text.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }

    private fun apiUrl(p: String): String {
        val base = api!!.optString("base").trimEnd('/')
        return if (p.startsWith("http")) p else base + (if (p.startsWith("/")) p else "/$p")
    }

    private fun apiCards(arr: JSONArray?, type: TvType): List<SearchResponse> {
        arr ?: return emptyList()
        val a = api!!
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.opt(i) as? JSONObject ?: return@mapNotNull null
            val itemId = field(o, a.optString("itemId"), "id", "_id", "slug", "animeId", "anime_id", "dataId") ?: return@mapNotNull null
            val title = field(o, a.optString("itemTitle"), "title", "name", "jname", "titles.en", "title_english") ?: return@mapNotNull null
            val poster = field(o, a.optString("itemPoster"), "poster", "image", "cover", "coverImage", "thumbnail", "img", "posterUrl", "poster_path")
            newMovieSearchResponse(title, "scraper://$id/${enc(itemId)}?t=${type.name}", type, fix = false) { posterUrl = poster }
        }
    }

    // ---------------------------------------------------------------- MainAPI

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val cat = try {
            JSONObject(request.data)
        } catch (t: Throwable) {
            JSONObject()
        }
        val type = typeOf(cat.optString("type").ifBlank { cfg.optJSONObject("detail")?.optString("type") })
        if (isApi) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val a = api!!
            val p = cat.optString("path").ifBlank { a.optString("homePath").ifBlank { "/home" } }
            val root = json(getText(apiUrl(p)))
            val arr = if (cat.optString("path").isNotBlank()) arrayIn(root, cat.optString("items")) else
                arrayIn(root, cat.optString("items").ifBlank { null }, cat.optString("id"), "data." + cat.optString("id"), "results." + cat.optString("id"))
            return newHomePageResponse(HomePageList(request.name, apiCards(arr, type)), false)
        }
        val s = cfg.optJSONObject("search") ?: JSONObject()
        val url = cat.optString("url").ifBlank { null }
        if (url == null && page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val full = abs((url ?: cfg.optString("homeUrl").ifBlank { "/" }).replace("{page}", page.toString()))!!
        val cards = htmlCards(getDoc(full), cat.optString("item").ifBlank { s.optString("item") },
            cat.optString("title").ifBlank { s.optString("title") }, cat.optString("href").ifBlank { s.optString("href") },
            cat.optString("poster").ifBlank { s.optString("poster") }, type)
        return newHomePageResponse(HomePageList(request.name, cards), url?.contains("{page}") == true && cards.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (isApi) {
            val a = api!!
            val p = a.optString("searchPath").ifBlank { "/search" }
            val url = if (p.contains("{query}")) apiUrl(p.replace("{query}", enc(query)).replace("{page}", "1"))
            else apiUrl(p) + (if (p.contains("?")) "&" else "?") + a.optString("searchParam").ifBlank { "q" } + "=" + enc(query)
            val type = typeOf((a.optJSONArray("catalogs")?.optJSONObject(0))?.optString("type"))
            return apiCards(arrayIn(json(getText(url)), a.optString("searchResults").ifBlank { null }), type)
        }
        val s = cfg.optJSONObject("search") ?: return emptyList()
        val url = abs(s.optString("url").ifBlank { "/?s={query}" }.replace("{query}", enc(query)).replace("{page}", "1"))!!
        return htmlCards(getDoc(url), s.optString("item"), s.optString("title"), s.optString("href"), s.optString("poster"),
            typeOf(cfg.optJSONObject("detail")?.optString("type")))
    }

    override suspend fun load(url: String): LoadResponse? = if (isApi) loadApi(url) else loadHtml(url)

    private suspend fun loadHtml(url: String): LoadResponse? {
        val d = cfg.optJSONObject("detail") ?: JSONObject()
        val doc = getDoc(url)
        val title = pick(doc, d.optString("title").ifBlank { "h1" }) ?: doc.title()
        val poster = abs(pick(doc, d.optString("poster").ifBlank { "meta[property=og:image]@content" }))
        val plot = pick(doc, d.optString("overview").ifBlank { "meta[name=description]@content" })
        val episodes = htmlEpisodes(url, doc)
        val type = typeOf(d.optString("type").ifBlank { if (episodes.isEmpty()) "movie" else "series" })
        return if (episodes.isEmpty() || type == TvType.Movie) {
            newMovieLoadResponse(title, url, if (type == TvType.Movie) TvType.Movie else type, url) { posterUrl = poster; this.plot = plot }
        } else {
            newTvSeriesLoadResponse(title, url, type, episodes) { posterUrl = poster; this.plot = plot }
        }
    }

    suspend fun htmlEpisodes(url: String, detailDoc: Document?): List<Episode> {
        val e = cfg.optJSONObject("episodes") ?: return emptyList()
        val item = e.optString("item").ifBlank { return emptyList() }
        val src = e.optString("url").ifBlank { "{href}" }
        val doc = if (src == "{href}" && detailDoc != null) detailDoc else getDoc(abs(src.replace("{href}", url))!!)
        return doc.select(item).mapIndexedNotNull { i, el ->
            val href = abs(pick(el, e.optString("href").ifBlank { "a@href" }) ?: return@mapIndexedNotNull null) ?: return@mapIndexedNotNull null
            val num = pick(el, e.optString("number"))?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: (i + 1)
            val season = pick(el, e.optString("season"))?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            newEpisode(href, fix = false) {
                episode = num
                this.season = season
                name = pick(el, e.optString("name").ifBlank { e.optString("title") })
            }
        }.distinctBy { it.data }
    }

    private suspend fun loadApi(url: String): LoadResponse? {
        val a = api!!
        val itemId = dec(url.substringAfter("scraper://$id/").substringBefore('?'))
        val type = url.substringAfter("?t=", "").let { t -> TvType.values().firstOrNull { it.name == t } } ?: TvType.TvSeries
        val detail = try {
            val root = json(getText(apiUrl(a.optString("detailPath").ifBlank { "/anime/{id}" }.replace("{id}", itemId))))
            path(root, a.optString("detailData").ifBlank { null }) ?: root
        } catch (t: Throwable) {
            null
        }
        val title = field(detail, a.optString("detailTitle"), "title", "name", "jname", "titles.en") ?: itemId
        val poster = field(detail, a.optString("detailPoster"), "poster", "image", "cover", "coverImage", "thumbnail")
        val plot = field(detail, a.optString("detailOverview"), "description", "synopsis", "overview", "plot", "desc")
        val episodes = apiEpisodes(itemId)
        return if (type == TvType.Movie && episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, JSONObject().put("id", itemId).put("ep", episodes.firstOrNull()?.episode ?: 1).toString()) {
                posterUrl = poster; this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, type, episodes) { posterUrl = poster; this.plot = plot }
        }
    }

    suspend fun apiEpisodes(itemId: String): List<Episode> {
        val a = api!!
        val p = a.optString("episodesPath").ifBlank { return emptyList() }.replace("{id}", itemId)
        val out = ArrayList<Episode>()
        val max = a.optInt("episodesMaxPages", 1).coerceIn(1, 100)
        val param = a.optString("episodesPageParam")
        for (page in 1..max) {
            val url = apiUrl(p.replace("{page}", page.toString())) +
                if (param.isNotBlank() && !p.contains("{page}")) (if (p.contains("?")) "&" else "?") + "$param=$page" else ""
            val root = try {
                json(getText(url))
            } catch (t: Throwable) {
                break
            }
            val arr = arrayIn(root, a.optString("episodesItems").ifBlank { null }, "data." + a.optString("episodesItems"))
            if (arr == null || arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.opt(i) as? JSONObject ?: continue
                val num = field(o, a.optString("episodeNumber"), "number", "episode", "ep", "episodeNumber")?.toDoubleOrNull()?.toInt() ?: (out.size + 1)
                val epId = field(o, a.optString("episodeId"), "id", "episodeId") ?: num.toString()
                val epName = field(o, a.optString("episodeName"), "title", "name")
                out.add(newEpisode(JSONObject().put("id", itemId).put("ep", num).put("epId", epId).toString(), fix = false) {
                    episode = num
                    name = epName
                })
            }
            val hasNext = a.optString("episodesHasNext").takeIf { it.isNotBlank() }?.let { k ->
                textOf(path(root, k) ?: path(root, "data.$k"))?.let { it == "true" || (it.toIntOrNull() ?: 0) > page }
            } ?: false
            if (!hasNext || param.isBlank() && !p.contains("{page}")) break
        }
        return out.distinctBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = if (isApi) linksApi(data, subtitleCallback, callback) else linksHtml(data, subtitleCallback, callback)

    private val m3u8Regex = Regex("""https?:[^"'\s<>\\]+?\.m3u8[^"'\s<>\\]*""")

    private suspend fun linksHtml(url: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val s = cfg.optJSONObject("streams") ?: JSONObject()
        val html = getText(url)
        val doc = Jsoup.parse(html, url)
        var found = false
        val seen = HashSet<String>()
        suspend fun direct(link: String) {
            val l = abs(link) ?: return
            if (!seen.add(l)) return
            found = true
            val m3u8 = l.substringBefore('?').endsWith(".m3u8", true)
            cb(newExtractorLink(name, name, l, if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = url
                quality = getQualityFromName(l)
                headers = hdrs()
            })
        }
        fun all(sel: String?): List<String> {
            val v = sel?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val at = v.lastIndexOf('@')
            return try {
                if (at > 0) doc.select(v.substring(0, at)).mapNotNull { attrOf(it, v.substring(at + 1)) }
                else doc.select(v).mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
            } catch (t: Throwable) {
                emptyList()
            }
        }
        all(s.optString("video")).forEach { direct(it) }
        all(s.optString("m3u8")).forEach { direct(it) }
        if (!found) m3u8Regex.findAll(html).map { it.value.replace("\\/", "/") }.take(5).toList().forEach { direct(it) }
        for (frame in all(s.optString("iframe").ifBlank { "iframe@src" })) {
            val f = abs(frame) ?: continue
            if (!seen.add(f)) continue
            if (loadExtractor(f, url, sub, cb)) found = true
        }
        return found
    }

    private suspend fun linksApi(data: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val a = api!!
        val d = JSONObject(data)
        val langs = a.optString("streamsLangs").ifBlank { "" }.split(',').map { it.trim() }.ifEmpty { listOf("") }
        var found = false
        for (lang in langs) {
            val p = a.optString("streamsPath").ifBlank { return false }
                .replace("{id}", d.optString("id")).replace("{ep}", d.optString("ep"))
                .replace("{epId}", d.optString("epId")).replace("{lang}", lang)
            val root = try {
                json(getText(apiUrl(p)))
            } catch (t: Throwable) {
                continue
            }
            val sources = arrayIn(root, a.optString("streamsSources").ifBlank { null }, "data." + a.optString("streamsSources")) ?: continue
            val extra = (path(root, a.optString("streamsHeaders")) ?: path(root, "data." + a.optString("streamsHeaders"))) as? JSONObject
            val h = HashMap<String, String>()
            extra?.keys()?.forEach { k -> h[k] = extra.optString(k) }
            for (i in 0 until sources.length()) {
                val o = sources.opt(i) as? JSONObject ?: continue
                var url = field(o, a.optString("streamsUrl"), "url", "file", "src") ?: continue
                val isM3u8 = field(o, a.optString("streamsIsM3u8"), "isM3U8", "isM3u8")?.let { it == "true" } ?: url.contains(".m3u8")
                val proxy = a.optString("proxy")
                if (a.optBoolean("proxyStreams") && proxy.isNotBlank()) {
                    url = a.optString("proxyFormat").ifBlank { "{proxy}?url={url}&headers={headers}" }
                        .replace("{proxy}", proxy).replace("{url}", enc(url)).replace("{headers}", enc(JSONObject(h as Map<*, *>).toString()))
                }
                val q = field(o, a.optString("streamsQuality"), "quality", "label")
                found = true
                cb(newExtractorLink(name, listOf(name, lang.uppercase(), q).filter { !it.isNullOrBlank() }.joinToString(" · "), url,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    quality = getQualityFromName(q)
                    if (!a.optBoolean("proxyStreams")) {
                        headers = h
                        h["Referer"]?.let { referer = it }
                    }
                })
            }
            val subs = arrayIn(root, a.optString("streamsSubtitles").ifBlank { null }, "data." + a.optString("streamsSubtitles"))
            if (subs != null && a.optString("streamsSubtitles").isNotBlank()) for (i in 0 until subs.length()) {
                val o = subs.opt(i) as? JSONObject ?: continue
                val su = field(o, a.optString("streamsSubUrl"), "url", "file") ?: continue
                val sl = field(o, a.optString("streamsSubLang"), "lang", "label", "language") ?: "?"
                if (sl.contains("thumbnail", true)) continue
                sub(newSubtitleFile(sl, su))
            }
        }
        return found
    }

    /** For the test page: other selectors on the page that look like a list of episodes. */
    fun episodeHints(doc: Document): List<String> {
        val counts = LinkedHashMap<String, Int>()
        doc.select("[class]").forEach { el ->
            el.classNames().filter { c -> Regex("ep|bolum|bölüm|episode|part", RegexOption.IGNORE_CASE).containsMatchIn(c) }.forEach { c ->
                if (el.selectFirst("a[href]") != null || el.tagName() == "a") {
                    val key = el.tagName() + "." + c
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
        }
        return counts.filter { it.value >= 2 }.entries.sortedByDescending { it.value }.take(3).map { "${it.key} (${it.value})" }
    }

    suspend fun docOf(url: String) = getDoc(url)
}
