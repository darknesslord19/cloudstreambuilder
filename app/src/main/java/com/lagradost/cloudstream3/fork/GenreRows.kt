package com.lagradost.cloudstream3.fork

import android.content.Context
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Extra categories for a source: the items the source shows on its home page are sorted by their
 * TMDB genres, and every chosen genre becomes a row of its own ("Aksiyon", "Komedi" …). The user can
 * also write a category ("Marvel", "90'lar Aksiyon"): words in the title, genres, years, rating and
 * country, and, when +18 is on, the +18 categories of Plus are made from the +18 rows.
 */
object GenreRows {
    private const val PREFS = "fork_genre_rows"

    /** Genre name and the TMDB ids that count as it (films and series have some of their own). */
    class Genre(val name: String, val ids: Set<Int>)

    val genres = listOf(
        Genre("Aksiyon", setOf(28, 10759)),
        Genre("Macera", setOf(12, 10759)),
        Genre("Animasyon", setOf(16)),
        Genre("Komedi", setOf(35)),
        Genre("Suç", setOf(80)),
        Genre("Belgesel", setOf(99)),
        Genre("Dram", setOf(18)),
        Genre("Aile", setOf(10751)),
        Genre("Fantastik", setOf(14, 10765)),
        Genre("Tarih", setOf(36)),
        Genre("Korku", setOf(27)),
        Genre("Müzik", setOf(10402)),
        Genre("Gizem", setOf(9648)),
        Genre("Romantik", setOf(10749)),
        Genre("Bilim Kurgu", setOf(878, 10765)),
        Genre("Gerilim", setOf(53)),
        Genre("Savaş", setOf(10752, 10768)),
        Genre("Western", setOf(37)),
        Genre("Çocuk", setOf(10762, 10751)),
        Genre("Pembe Dizi", setOf(10766)),
        Genre("Gerçeklik", setOf(10764)),
        Genre("Talk Show", setOf(10767)),
    )

    // ---------------------------------------------------------------- written by the user

    class Custom(
        val name: String,
        val words: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val all: Boolean = false,
        val from: Int? = null,
        val to: Int? = null,
        val rating: Double? = null,
        val country: String = "all",
        val adult: Boolean = false,
    ) {
        /** Only the words of the title: works without a TMDB key. */
        val needsTmdb get() = genres.isNotEmpty() || rating != null || country != "all" || from != null || to != null

        fun json(): JSONObject = JSONObject().put("n", name).put("w", JSONArray(words)).put("g", JSONArray(genres)).put("all", all)
            .put("from", from ?: JSONObject.NULL).put("to", to ?: JSONObject.NULL).put("r", rating ?: JSONObject.NULL)
            .put("c", country).put("a", adult)

        /** A short line for the lists: "Başlıkta: marvel · Aksiyon · 1990–1999" */
        fun summary(): String {
            val parts = ArrayList<String>()
            if (words.isNotEmpty()) parts.add("Başlıkta: " + words.joinToString(", "))
            if (genres.isNotEmpty()) parts.add(genres.joinToString(if (all) " + " else " / "))
            if (from != null || to != null) parts.add("${from ?: "…"}–${to ?: "…"}")
            if (rating != null) parts.add("puan ${rating.toString().removeSuffix(".0")}+")
            GenreRows.countries[country]?.takeIf { country != "all" }?.let { parts.add(it.first) }
            return parts.joinToString(" · ")
        }

        companion object {
            fun from(o: JSONObject): Custom {
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                fun int(k: String) = if (o.isNull(k)) null else o.optInt(k).takeIf { it > 0 }
                return Custom(
                    o.optString("n"), list("w"), list("g"), o.optBoolean("all"), int("from"), int("to"),
                    if (o.isNull("r")) null else o.optDouble("r").takeIf { !it.isNaN() && it > 0 },
                    o.optString("c", "all").ifBlank { "all" }, o.optBoolean("a"),
                )
            }
        }
    }

    /** country choice -> name, languages, TMDB countries */
    val countries = linkedMapOf(
        "all" to Triple("Hepsi", emptySet<String>(), emptySet<String>()),
        "tr" to Triple("Türkiye", setOf("tr"), setOf("TR")),
        "ko" to Triple("Kore", setOf("ko"), setOf("KR")),
        "ja" to Triple("Japonya", setOf("ja"), setOf("JP")),
        "in" to Triple("Hindistan", setOf("hi", "ta", "te", "ml", "kn"), setOf("IN")),
        "us" to Triple("ABD / İngiltere", setOf("en"), setOf("US", "GB")),
    )

    fun customs(context: Context): List<Custom> = try {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("__custom", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Custom.from(it) }.filter { it.name.isNotBlank() }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun saveCustoms(context: Context, list: List<Custom>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("__custom", JSONArray(list.map { it.json() }).toString()).apply()
        forget()
    }

    /** Adds or replaces (by [oldName], or by the same name). */
    fun saveCustom(context: Context, c: Custom, oldName: String? = null) {
        val list = customs(context).filter { it.name != c.name && it.name != oldName }
        saveCustoms(context, list + c)
    }

    fun removeCustom(context: Context, name: String) {
        saveCustoms(context, customs(context).filter { it.name != name })
    }

    /** A +18 category on the home page is chosen as "18:<name>". */
    const val ADULT_PREFIX = "18:"

    // ---------------------------------------------------------------- making the rows

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** source -> (what was asked, row name -> items) */
    private val made = ConcurrentHashMap<String, Pair<String, Map<String, List<SearchResponse>>>>()
    private val making = ConcurrentHashMap.newKeySet<String>()

    /** TMDB details of the cards, by url */
    private val infos = ConcurrentHashMap<String, TmdbPosters.Info>()
    private val noInfo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var onReady: (() -> Unit)? = null

    fun forget() = made.clear()

    fun chosen(context: Context, api: String?): List<String> {
        api ?: return emptyList()
        return try {
            val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(api, "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun setChosen(context: Context, api: String, names: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(api, JSONArray(names).toString()).apply()
        made.remove(api)
    }

    fun canWork(context: Context): Boolean = TmdbPosters.key(context) != null

    private fun cardYear(card: SearchResponse): Int? = when (card) {
        is MovieSearchResponse -> card.year
        is TvSeriesSearchResponse -> card.year
        is AnimeSearchResponse -> card.year
        else -> null
    }

    private fun cardMovie(card: SearchResponse): Boolean? = when (card.type) {
        com.lagradost.cloudstream3.TvType.Movie, com.lagradost.cloudstream3.TvType.AnimeMovie -> true
        com.lagradost.cloudstream3.TvType.TvSeries, com.lagradost.cloudstream3.TvType.Anime,
        com.lagradost.cloudstream3.TvType.AsianDrama, com.lagradost.cloudstream3.TvType.Cartoon -> false
        else -> null
    }

    private val tr = Locale("tr")

    /** Whether [card] belongs in [c]; null info means TMDB did not know it (or there is no key). */
    fun matches(c: Custom, card: SearchResponse, info: TmdbPosters.Info?): Boolean {
        if (c.words.isNotEmpty()) {
            val n = card.name.lowercase(tr)
            if (c.words.none { n.contains(it.lowercase(tr).trim()) }) return false
        }
        if (c.genres.isNotEmpty()) {
            info ?: return false
            val sets = c.genres.mapNotNull { name -> genres.firstOrNull { it.name == name }?.ids }
            if (sets.isEmpty()) return false
            val hit = { ids: Set<Int> -> info.genres.any { it in ids } }
            if (if (c.all) !sets.all(hit) else !sets.any(hit)) return false
        }
        if (c.from != null || c.to != null) {
            val y = info?.year ?: cardYear(card) ?: return false
            if (c.from != null && y < c.from) return false
            if (c.to != null && y > c.to) return false
        }
        if (c.rating != null && (info == null || info.rating < c.rating)) return false
        if (c.country != "all") {
            info ?: return false
            val (_, langs, lands) = countries[c.country] ?: return false
            if (info.lang !in langs && info.countries.none { it in lands }) return false
        }
        return true
    }

    /** How many of [items] [c] would take with what is known now, and whether some are still unknown. */
    fun count(c: Custom, items: List<SearchResponse>): Pair<Int, Boolean> {
        val unique = items.distinctBy { it.url }
        val n = unique.count { matches(c, it, infos[it.url]) }
        val unknown = c.needsTmdb && unique.any { it.url !in infos && it.url !in noInfo }
        return n to unknown
    }

    private suspend fun fill(context: Context, cards: List<SearchResponse>, adult: Boolean) {
        val todo = cards.filter { it.url !in infos && it.url !in noInfo }
        if (todo.isEmpty() || !canWork(context)) return
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        kotlinx.coroutines.coroutineScope {
            todo.map { card ->
                async {
                    gate.acquire()
                    try {
                        val i = TmdbPosters.infoOf(context, card, adult)
                        if (i != null) infos[card.url] = i else noInfo.add(card.url)
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
    }

    /** Asks TMDB about [items] in the background (the form shows the count when it is done). */
    fun prefetch(context: Context, items: List<SearchResponse>, done: () -> Unit) {
        if (!canWork(context)) return
        scope.launch {
            fill(context, items.distinctBy { it.url }.filter { !TraktRecs.isRec(it) }, false)
            withContext(Dispatchers.Main) { done() }
        }
    }

    /**
     * The extra rows for [api]: genres and written categories from [items], +18 categories from
     * [adultItems]. They are made in the background the first time.
     */
    fun rows(context: Context, api: String?, items: List<SearchResponse>, adultItems: List<SearchResponse> = emptyList()): List<HomePageList> {
        api ?: return emptyList()
        val showAdult = Adult.visible(context)
        val adultCats = if (showAdult) Adult.chosenCats(context) else emptySet()
        val mine = customs(context).filter { !it.adult || showAdult }.associateBy { it.name }
        val key = canWork(context)
        val wanted = chosen(context, api).filter { w ->
            when {
                w.startsWith(ADULT_PREFIX) -> w.removePrefix(ADULT_PREFIX) in adultCats
                mine.containsKey(w) -> true
                else -> key && genres.any { it.name == w }
            }
        }
        if (wanted.isEmpty() || (items.isEmpty() && adultItems.isEmpty())) return emptyList()
        val sign = wanted.joinToString("|") + "#" + mine.values.joinToString("|") { it.json().toString() } + "#" + items.size + "/" + adultItems.size
        val ready = made[api]
        val known = ready?.let { r -> wanted.mapNotNull { w -> r.second[w]?.takeIf { it.isNotEmpty() }?.let { HomePageList(w.removePrefix(ADULT_PREFIX), it, false) } } }
        if (ready != null && ready.first == sign) return known.orEmpty()
        if (making.add(api)) {
            val unique = items.distinctBy { it.url }.filter { !TraktRecs.isRec(it) }
            val adultUnique = adultItems.distinctBy { it.url }
            scope.launch {
                try {
                    val needInfo = wanted.any { w ->
                        when {
                            w.startsWith(ADULT_PREFIX) -> false
                            mine.containsKey(w) -> mine[w]!!.needsTmdb && !mine[w]!!.adult
                            else -> true
                        }
                    }
                    val needAdultInfo = wanted.any { w ->
                        (w.startsWith(ADULT_PREFIX) && Adult.cats.firstOrNull { it.name == w.removePrefix(ADULT_PREFIX) }?.needsTmdb == true) ||
                                (mine[w]?.let { it.adult && it.needsTmdb } == true)
                    }
                    if (needInfo) fill(context, unique, false)
                    if (needAdultInfo) fill(context, adultUnique, true)
                    val result = HashMap<String, List<SearchResponse>>()
                    for (w in wanted) {
                        result[w] = when {
                            w.startsWith(ADULT_PREFIX) -> {
                                val cat = Adult.cats.firstOrNull { it.name == w.removePrefix(ADULT_PREFIX) } ?: continue
                                adultUnique.filter { cat.test(infos[it.url], cardMovie(it)) }
                            }
                            mine.containsKey(w) -> {
                                val c = mine[w]!!
                                (if (c.adult) adultUnique else unique).filter { matches(c, it, infos[it.url]) }
                            }
                            else -> {
                                val genre = genres.firstOrNull { it.name == w } ?: continue
                                unique.filter { card -> infos[card.url]?.genres?.any { it in genre.ids } == true }
                            }
                        }
                    }
                    made[api] = sign to result
                    withContext(Dispatchers.Main) { onReady?.invoke() }
                } finally {
                    making.remove(api)
                }
            }
        }
        // the rows made before stay until the new ones are ready
        return known.orEmpty()
    }
}
