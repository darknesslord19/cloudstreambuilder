package com.lagradost.cloudstream3.fork

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Posters from TMDB for the items that have none, or whose picture does not open.
 *
 * The key is the developer's one from Cloudstreamplus.json ("tmdb_api_key", "tmdb_api_key_on"),
 * or when that is missing or off, the user's own one saved in Plus. Switched off from outside
 * with "tmdb_posters". What was found is kept, an item is asked about once.
 */
object TmdbPosters {
    private const val TAG = "TmdbPosters"

    const val PREF_ON = "fork_tmdb"
    const val PREF_HOME = "fork_tmdb_home"
    const val PREF_CATS = "fork_tmdb_cats"
    const val PREF_HERO = "fork_tmdb_hero"
    const val PREF_BADGE = "fork_tmdb_badge"
    const val PREF_USER_KEY = "fork_tmdb_user_key"

    private const val CACHE = "fork_tmdb_cache"
    private const val NONE = "none:"
    private const val RETRY_MS = 3L * 24 * 60 * 60 * 1000

    private const val POSTER = "https://image.tmdb.org/t/p/w342"
    private const val BACKDROP = "https://image.tmdb.org/t/p/w780"

    enum class Where { HOME, CATEGORIES }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    private val memory = ConcurrentHashMap<String, String>()
    private val running = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    @Volatile
    private var memoryLoaded = false

    // ---------------------------------------------------------------- switches and the key

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** Not switched off from outside. */
    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.TMDB_POSTERS, context)

    /** The developer's key, when given and switched on. */
    fun adminKey(context: Context?): String? {
        val ctx = context ?: CloudStreamApp.context ?: return null
        val doc = ForkFeatures.general(ctx) ?: return null
        val key = RemoteRules.text(doc, "tmdb_api_key", "tmdb_key") ?: return null
        return if (RemoteRules.flagOf(doc, true, "tmdb_api_key_on", "tmdb_key_on")) key else null
    }

    fun userKey(context: Context): String? = prefs(context).getString(PREF_USER_KEY, null)?.trim()?.ifEmpty { null }

    fun key(context: Context?): String? {
        val ctx = context ?: CloudStreamApp.context ?: return null
        return adminKey(ctx) ?: userKey(ctx)
    }

    fun active(context: Context?, where: Where): Boolean {
        val ctx = context ?: CloudStreamApp.context ?: return false
        if (!available(ctx)) return false
        val p = prefs(ctx)
        if (!p.getBoolean(PREF_ON, true)) return false
        val place = when (where) {
            Where.HOME -> p.getBoolean(PREF_HOME, true)
            Where.CATEGORIES -> p.getBoolean(PREF_CATS, true)
        }
        return place && key(ctx) != null
    }

    fun heroActive(context: Context): Boolean = active(context, Where.HOME) && prefs(context).getBoolean(PREF_HERO, true)

    fun badgeShown(context: Context): Boolean = prefs(context).getBoolean(PREF_BADGE, false)

    // ---------------------------------------------------------------- names

    private val yearRegex = Regex("""\b(19[0-9]{2}|20[0-9]{2})\b""")
    private val bracketRegex = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*\}""")
    private val noiseRegex = Regex(
        """(?i)\b(izle|full ?hd|1080p|720p|480p|4k|uhd|hd|türkçe dublaj|turkce dublaj|türkçe altyazılı|turkce altyazili|altyazılı|altyazili|dublaj|tr dublaj|sezon \d+|season \d+|\d+\. ?sezon)\b"""
    )

    /** The name to look for and the year in it, if any. */
    fun clean(name: String): Pair<String, Int?> {
        val year = yearRegex.findAll(name).lastOrNull()?.value?.toIntOrNull()
        var text = name.replace(bracketRegex, " ").replace(noiseRegex, " ")
        val withoutYear = text.replace(yearRegex, " ")
        if (withoutYear.any { it.isLetter() }) text = withoutYear
        text = text.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '|', '–', '.', ',')
        return (text.ifEmpty { name.trim() }) to year
    }

    private fun yearOf(card: SearchResponse): Int? = when (card) {
        is MovieSearchResponse -> card.year
        is TvSeriesSearchResponse -> card.year
        is AnimeSearchResponse -> card.year
        else -> null
    }

    private fun kindOf(type: TvType?): String = when (type) {
        TvType.Movie, TvType.AnimeMovie, TvType.Torrent -> "movie"
        TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon -> "tv"
        else -> "multi"
    }

    private fun cacheKey(backdrop: Boolean, name: String, year: Int?, kind: String): String =
        (if (backdrop) "b|" else "p|") + kind + "|" + name.lowercase() + "|" + (year ?: "")

    // ---------------------------------------------------------------- keeping

    private fun loadMemory(context: Context) {
        if (memoryLoaded) return
        memoryLoaded = true
        try {
            context.applicationContext.getSharedPreferences(CACHE, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                (v as? String)?.let { memory[k] = it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cache unreadable", t)
        }
    }

    private fun keep(context: Context, key: String, value: String) {
        memory[key] = value
        context.applicationContext.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    /** A kept answer: the address, "" when known to be missing, null when not asked yet. */
    private fun kept(context: Context, key: String): String? {
        loadMemory(context)
        val value = memory[key] ?: return null
        if (value.startsWith(NONE)) {
            val at = value.removePrefix(NONE).toLongOrNull() ?: 0L
            return if (System.currentTimeMillis() - at < RETRY_MS) "" else null
        }
        return value
    }

    /** The kept TMDB poster of [card], without asking. */
    fun cachedPoster(context: Context, card: SearchResponse): String? {
        val (name, year) = clean(card.name)
        return kept(context, cacheKey(false, name, year ?: yearOf(card), kindOf(card.type)))?.ifEmpty { null }
    }

    // ---------------------------------------------------------------- asking TMDB

    private suspend fun request(key: String, path: String, params: Map<String, String>): JSONObject? {
        val bearer = key.startsWith("eyJ")
        val query = (if (bearer) params else params + ("api_key" to key)).entries.joinToString("&") { (k, v) ->
            k + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val url = "https://api.themoviedb.org/3/$path?$query"
        return try {
            val response = app.get(
                url,
                headers = if (bearer) mapOf("Authorization" to "Bearer $key", "accept" to "application/json")
                else mapOf("accept" to "application/json"),
            )
            if (response.code !in 200..299) null else JSONObject(response.text)
        } catch (t: Throwable) {
            Log.w(TAG, "request failed", t)
            null
        }
    }

    /** The details of a film ("movie") or a series ("tv") in Turkish, null without a key. */
    suspend fun details(context: Context, kind: String, id: Int): JSONObject? {
        val key = key(context) ?: return null
        return withContext(Dispatchers.IO) { request(key, "$kind/$id", mapOf("language" to "tr-TR")) }
    }

    /**
     * The TMDB genres of an item ("28,12"), kept like the posters. Null without a key or when
     * TMDB does not know it.
     */
    suspend fun genresOf(context: Context, card: SearchResponse): Set<Int>? {
        val key = key(context) ?: return null
        val (name, nameYear) = clean(card.name)
        if (name.isBlank()) return null
        val year = yearOf(card) ?: nameYear
        val kind = kindOf(card.type)
        val cacheKey = "g|" + kind + "|" + name.lowercase() + "|" + (year ?: "")
        kept(context, cacheKey)?.let { v -> return v.split(',').mapNotNull { it.toIntOrNull() }.toSet().ifEmpty { null } }
        val found = try {
            gate.withPermit {
                val params = hashMapOf("query" to name, "language" to "tr-TR", "include_adult" to "false")
                val results = request(key, "search/$kind", params)?.optJSONArray("results")
                val list = (0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it) }
                    .filter { kind != "multi" || it.optString("media_type") in setOf("movie", "tv") }
                val pick = (if (year != null) list.firstOrNull { r -> yearOfResult(r)?.let { kotlin.math.abs(it - year) <= 1 } == true } else null)
                    ?: list.firstOrNull()
                pick?.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }
            }
        } catch (t: Throwable) {
            null
        }
        keep(context, cacheKey, found?.joinToString(",") ?: (NONE + System.currentTimeMillis()))
        return found
    }

    fun posterOf(path: String?): String? = path?.takeIf { it.isNotBlank() && it != "null" }?.let { POSTER + it }

    /** Tries a key, true when TMDB accepts it. */
    suspend fun test(key: String): Boolean = withContext(Dispatchers.IO) {
        request(key.trim(), "configuration", emptyMap()) != null
    }

    private fun yearOfResult(r: JSONObject): Int? =
        (r.optString("release_date").ifEmpty { r.optString("first_air_date") }).take(4).toIntOrNull()

    private suspend fun search(key: String, kind: String, name: String, year: Int?, backdrop: Boolean): String? {
        val field = if (backdrop) "backdrop_path" else "poster_path"
        suspend fun ask(kind: String, withYear: Boolean): String? {
            val params = HashMap<String, String>()
            params["query"] = name
            params["language"] = "tr-TR"
            params["include_adult"] = "false"
            if (withYear && year != null) {
                when (kind) {
                    "movie" -> params["year"] = year.toString()
                    "tv" -> params["first_air_date_year"] = year.toString()
                }
            }
            val results = request(key, "search/$kind", params)?.optJSONArray("results") ?: return null
            val list = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { kind != "multi" || it.optString("media_type") in setOf("movie", "tv") }
                .filter { it.optString(field).let { p -> p.isNotEmpty() && p != "null" } }
            if (list.isEmpty()) return null
            val pick = if (year != null) {
                list.firstOrNull { r -> yearOfResult(r)?.let { kotlin.math.abs(it - year) <= 1 } == true }
                    ?: if (withYear) list.first() else null
            } else list.first()
            pick ?: return null
            return (if (backdrop) BACKDROP else POSTER) + pick.optString(field)
        }
        return ask(kind, true)
            ?: (if (year != null && kind != "multi") ask(kind, false) else null)
            ?: (if (kind != "multi") ask("multi", false) else null)
    }

    /**
     * The TMDB picture for a name, kept or asked for. Only one question per item at a time.
     * @return null when there is none or nothing could be asked.
     */
    suspend fun find(context: Context, rawName: String, type: TvType?, givenYear: Int?, backdrop: Boolean): String? {
        val key = key(context) ?: return null
        val (name, nameYear) = clean(rawName)
        if (name.isBlank()) return null
        val year = givenYear ?: nameYear
        val kind = kindOf(type)
        val cacheKey = cacheKey(backdrop, name, year, kind)
        kept(context, cacheKey)?.let { return it.ifEmpty { null } }

        val mine = CompletableDeferred<String?>()
        val other = running.putIfAbsent(cacheKey, mine)
        if (other != null) return other.await()
        return try {
            val found = gate.withPermit { search(key, kind, name, year, backdrop) }
            keep(context, cacheKey, found ?: (NONE + System.currentTimeMillis()))
            mine.complete(found)
            found
        } catch (t: Throwable) {
            mine.complete(null)
            null
        } finally {
            running.remove(cacheKey)
        }
    }

    // ---------------------------------------------------------------- the home cards

    /** Called for every card that is bound, so late answers for an old card are thrown away. */
    fun bind(view: ImageView, card: SearchResponse) {
        view.setTag(R.id.fork_tmdb_tag, card.url)
        badge(view, false)
    }

    /** The card has no picture or its picture did not open: looks for one on TMDB. */
    fun fill(view: ImageView, card: SearchResponse) {
        val context = view.context ?: return
        if (!active(context, Where.HOME)) return
        val wanted = card.url
        if (view.getTag(R.id.fork_tmdb_tag) != wanted) return
        scope.launch {
            val url = find(context, card.name, card.type, yearOf(card), false) ?: return@launch
            withContext(Dispatchers.Main) {
                if (view.getTag(R.id.fork_tmdb_tag) != wanted) return@withContext
                view.loadImage(url)
                badge(view, badgeShown(context))
            }
        }
    }

    /** The big card on top: a wide picture when the item has none. */
    fun fillHero(view: ImageView, name: String, type: TvType?, year: Int?, url: String?) {
        val context = view.context ?: return
        view.setTag(R.id.fork_tmdb_tag, url ?: name)
        if (!heroActive(context)) return
        val wanted = url ?: name
        scope.launch {
            val found = find(context, name, type, year, true) ?: find(context, name, type, year, false) ?: return@launch
            withContext(Dispatchers.Main) {
                if (view.getTag(R.id.fork_tmdb_tag) == wanted) view.loadImage(found)
            }
        }
    }

    /** The small "TMDB" label at the bottom right of a card. */
    private fun badge(view: ImageView, show: Boolean) {
        val parent = view.parent as? FrameLayout ?: return
        var label = parent.findViewById<TextView>(R.id.fork_tmdb_badge)
        if (!show) {
            label?.visibility = android.view.View.GONE
            return
        }
        if (label == null) {
            label = TextView(view.context).apply {
                id = R.id.fork_tmdb_badge
                text = "TMDB"
                textSize = 8.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#032541"))
                setPadding(4.toPx, 1.toPx, 4.toPx, 1.toPx)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#01B4E4"))
                    cornerRadius = 4.toPx.toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply { setMargins(5.toPx, 5.toPx, 5.toPx, 5.toPx) }
                elevation = 2f
            }
            parent.addView(label)
        }
        label.visibility = android.view.View.VISIBLE
        label.bringToFront()
    }
}
