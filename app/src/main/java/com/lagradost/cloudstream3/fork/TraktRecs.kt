package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Suggestions from Trakt, made from what was watched in the app: for the last titles their
 * related films and series on Trakt. Shown as the row "Sana özel" on the home page and, when
 * nothing was watched for a while, as one suggestion when the app opens.
 * The Trakt key ("client id") comes from Cloudstreamplus.json ("trakt_client_id") or is the
 * user's own. Switched off from outside with "trakt_recs".
 */
object TraktRecs {
    private const val TAG = "TraktRecs"
    const val PREF_ON = "fork_trakt"
    const val PREF_ROW = "fork_trakt_row"
    const val PREF_POPUP = "fork_trakt_popup"
    const val PREF_DAYS = "fork_trakt_days"
    const val PREF_USER_KEY = "fork_trakt_user_key"

    private const val CACHE = "fork_trakt_cache"
    private const val REFRESH_MS = 12L * 60 * 60 * 1000
    const val URL_PREFIX = "trakt-rec://"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Rec(val title: String, val year: Int?, val movie: Boolean, val poster: String?, val reason: String)

    @Volatile
    private var recs: List<Rec>? = null

    @Volatile
    private var loading = false

    /** Told when new suggestions are there, the home page draws its rows again. */
    @Volatile
    var onReady: (() -> Unit)? = null

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.TRAKT_RECS, context)

    fun on(context: Context): Boolean = available(context) && prefs(context).getBoolean(PREF_ON, true) && key(context) != null
    fun rowOn(context: Context) = on(context) && prefs(context).getBoolean(PREF_ROW, true)
    fun popupOn(context: Context) = on(context) && prefs(context).getBoolean(PREF_POPUP, true)

    fun adminKey(context: Context): String? {
        val doc = ForkFeatures.general(context) ?: return null
        val key = RemoteRules.text(doc, "trakt_client_id", "trakt_key") ?: return null
        return if (RemoteRules.flagOf(doc, true, "trakt_client_id_on", "trakt_key_on")) key else null
    }

    fun userKey(context: Context): String? = prefs(context).getString(PREF_USER_KEY, null)?.trim()?.ifEmpty { null }

    fun key(context: Context): String? = adminKey(context) ?: userKey(context)

    // ---------------------------------------------------------------- asking Trakt

    private suspend fun trakt(key: String, path: String): String? = try {
        val r = app.get(
            "https://api.trakt.tv/$path",
            headers = mapOf("trakt-api-key" to key, "trakt-api-version" to "2", "Content-Type" to "application/json"),
            timeout = 15L
        )
        if (r.code in 200..299) r.text else null
    } catch (t: Throwable) {
        Log.w(TAG, "trakt failed", t)
        null
    }

    /** Tries a key, true when Trakt accepts it. */
    suspend fun test(key: String): Boolean = withContext(Dispatchers.IO) { trakt(key.trim(), "movies/trending?limit=1") != null }

    private suspend fun build(context: Context): List<Rec> {
        val key = key(context) ?: return emptyList()
        // what was watched last, then what was bookmarked
        val seeds = LinkedHashMap<String, Boolean>()
        try {
            HomeViewModel.getResumeWatching()?.take(6)?.forEach { seeds[it.name] = it.type?.let { t -> t == TvType.Movie || t == TvType.AnimeMovie } ?: true }
        } catch (t: Throwable) {
        }
        try {
            DataStoreHelper.getAllBookmarkedData().sortedByDescending { it.latestUpdatedTime }.take(6).forEach {
                if (!seeds.containsKey(it.name)) seeds[it.name] = it.type?.let { t -> t == TvType.Movie || t == TvType.AnimeMovie } ?: true
            }
        } catch (t: Throwable) {
        }
        val out = LinkedHashMap<String, Rec>()
        val seen = seeds.keys.map { it.lowercase() }.toSet()
        for ((seed, movie) in seeds.entries.take(5)) {
            val kind = if (movie) "movie" else "show"
            val found = trakt(key, "search/$kind?limit=1&query=" + URLEncoder.encode(seed, "UTF-8")) ?: continue
            val item = JSONArray(found).optJSONObject(0)?.optJSONObject(kind) ?: continue
            val slug = item.optJSONObject("ids")?.optString("slug")?.ifEmpty { null } ?: continue
            val related = trakt(key, "${kind}s/$slug/related?limit=8") ?: continue
            val list = JSONArray(related)
            for (i in 0 until list.length()) {
                val r = list.optJSONObject(i) ?: continue
                val title = r.optString("title").ifEmpty { null } ?: continue
                if (title.lowercase() in seen || out.containsKey(title.lowercase())) continue
                val tmdb = r.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
                var shownTitle = title
                var poster: String? = null
                if (tmdb > 0) {
                    TmdbPosters.details(context, if (movie) "movie" else "tv", tmdb)?.let { d ->
                        poster = TmdbPosters.posterOf(d.optString("poster_path"))
                        shownTitle = d.optString(if (movie) "title" else "name").ifEmpty { title }
                    }
                }
                out[title.lowercase()] = Rec(shownTitle, r.optInt("year", 0).takeIf { it > 0 }, movie, poster, "$seed izlediğin için")
            }
            if (out.size >= 24) break
        }
        // nothing watched yet: what is popular now
        if (out.isEmpty()) {
            trakt(key, "movies/trending?limit=15")?.let { text ->
                val list = JSONArray(text)
                for (i in 0 until list.length()) {
                    val m = list.optJSONObject(i)?.optJSONObject("movie") ?: continue
                    val title = m.optString("title").ifEmpty { null } ?: continue
                    val tmdb = m.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
                    var poster: String? = null
                    var shownTitle = title
                    if (tmdb > 0) TmdbPosters.details(context, "movie", tmdb)?.let { d ->
                        poster = TmdbPosters.posterOf(d.optString("poster_path")); shownTitle = d.optString("title").ifEmpty { title }
                    }
                    out[title.lowercase()] = Rec(shownTitle, m.optInt("year", 0).takeIf { it > 0 }, true, poster, "Şu an popüler")
                }
            }
        }
        return out.values.toList()
    }

    private fun save(context: Context, list: List<Rec>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("y", it.year ?: 0).put("m", it.movie).put("p", it.poster ?: "").put("r", it.reason)) }
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
            .putString("recs", a.toString()).putLong("at", System.currentTimeMillis()).apply()
    }

    private fun load(context: Context): List<Rec> = try {
        val a = JSONArray(context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getString("recs", "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Rec(it.optString("t"), it.optInt("y").takeIf { y -> y > 0 }, it.optBoolean("m"), it.optString("p").ifEmpty { null }, it.optString("r")) }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /** The suggestions, read again from Trakt when they are old. */
    fun refresh(context: Context) {
        if (!on(context)) return
        if (recs == null) recs = load(context)
        val at = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getLong("at", 0L)
        if (loading || (System.currentTimeMillis() - at < REFRESH_MS && !recs.isNullOrEmpty())) return
        loading = true
        scope.launch {
            try {
                val fresh = build(context)
                if (fresh.isNotEmpty()) {
                    recs = fresh
                    save(context, fresh)
                    withContext(Dispatchers.Main) { onReady?.invoke() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed", t)
            } finally {
                loading = false
            }
        }
    }

    // ---------------------------------------------------------------- the home page row

    private object Source : MainAPI() {
        override var name = "Trakt"
        override var mainUrl = "https://trakt.tv"
    }

    private fun card(rec: Rec): SearchResponse =
        Source.newMovieSearchResponse(rec.title, URL_PREFIX + URLEncoder.encode(rec.title, "UTF-8"), if (rec.movie) TvType.Movie else TvType.TvSeries, fix = false) {
            posterUrl = rec.poster
            year = rec.year
        }

    /** The row "Sana özel" for the top of the home page, null when there is nothing to show. */
    fun row(context: Context): HomePageList? {
        if (!rowOn(context)) return null
        refresh(context)
        val list = recs?.takeIf { it.isNotEmpty() } ?: return null
        return HomePageList(context.getString(R.string.trakt_row), list.map { card(it) }, false)
    }

    fun isRec(card: SearchResponse?): Boolean = card?.url?.startsWith(URL_PREFIX) == true

    /** A suggestion is looked for in the plugins: the search opens with its name. */
    fun open(activity: Activity?, title: String) {
        activity ?: return
        MainActivity.nextSearchQuery = title
        activity.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_search
        activity.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_search
    }

    // ---------------------------------------------------------------- the suggestion when the app opens

    @Volatile
    private var askedThisStart = false

    private fun lastWatched(): Long = try {
        DataStoreHelper.getAllResumeStateIds()?.mapNotNull { DataStoreHelper.getLastWatched(it)?.updateTime }?.maxOrNull() ?: 0L
    } catch (t: Throwable) {
        0L
    }

    fun maybeSuggest(activity: Activity) {
        if (askedThisStart || !popupOn(activity)) return
        askedThisStart = true
        val p = prefs(activity)
        val days = p.getString(PREF_DAYS, "3")?.toIntOrNull() ?: 3
        val now = System.currentTimeMillis()
        if (now - p.getLong("fork_trakt_popup_at", 0L) < 20L * 60 * 60 * 1000) return
        val last = lastWatched()
        if (last == 0L || now - last < days * 24L * 60 * 60 * 1000) return
        val list = (recs ?: load(activity)).takeIf { it.isNotEmpty() } ?: return
        p.edit().putLong("fork_trakt_popup_at", now).apply()
        show(activity, list, 0)
    }

    private fun show(activity: Activity, list: List<Rec>, index: Int) {
        val rec = list[index % list.size]
        val d = activity.resources.displayMetrics.density
        val text = activity.colorFromAttribute(R.attr.textColor)
        val gray = activity.colorFromAttribute(R.attr.grayTextColor)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.toPx, 18.toPx, 22.toPx, 6.toPx)
        }
        box.addView(TextView(activity).apply {
            setText(R.string.trakt_popup_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text)
        })
        box.addView(TextView(activity).apply {
            setText(R.string.trakt_popup_des)
            textSize = 13f
            setTextColor(gray)
            setPadding(0, 2.toPx, 0, 12.toPx)
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val poster = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(activity.colorFromAttribute(R.attr.boxItemBackground)) }
        }
        rec.poster?.let { poster.loadImage(it) }
        row.addView(poster, LinearLayout.LayoutParams(96.toPx, 140.toPx).apply { marginEnd = 14.toPx })
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                this.text = rec.title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(text)
            })
            addView(TextView(activity).apply {
                this.text = listOfNotNull(rec.year?.toString(), activity.getString(if (rec.movie) R.string.trakt_movie else R.string.trakt_series)).joinToString(" · ")
                textSize = 12.5f
                setTextColor(gray)
                setPadding(0, 2.toPx, 0, 10.toPx)
            })
            addView(TextView(activity).apply {
                this.text = rec.reason
                textSize = 13f
                setTextColor(text)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setView(box)
            .setPositiveButton(R.string.trakt_watch) { _, _ -> open(activity, rec.title) }
            .setNeutralButton(R.string.trakt_other) { _, _ -> show(activity, list, index + 1) }
            .setNegativeButton(R.string.trakt_close, null)
            .create()
        dialog.show()
        // the announcement blocker never takes this one
        dialog.window?.decorView?.setTag(R.id.fork_adblock_own, true)
    }
}
