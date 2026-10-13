package com.lagradost.cloudstream3.fork

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.settings.AssetPage
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The categories screen: big moving category cards that gather films and series from every
 * added repository. The screen is the bundled page assets/categories.html, this is what it
 * asks the app for. It can be switched off from outside with "categories".
 */
object Categories {
    /** Show the icon on the home screen */
    const val PREF_ICON = "fork_categories_icon"

    /** Let the cards move */
    const val PREF_ANIM = "fork_categories_anim"

    private const val PAGE = "categories.html"

    /** At most this many repositories are asked at the same time */
    private const val PARALLEL = 4

    /** A repository that does not answer in this time is left out */
    private const val TIMEOUT_MS = 20_000L

    /** Sections of one repository asked for one category */
    private const val SECTIONS_PER_SOURCE = 2

    /** How long gathered results are kept */
    private const val CACHE_MS = 20 * 60 * 1000L
    private const val CACHE_ENTRIES = 30

    /** What the page keeps is saved under this prefix in the settings */
    private const val STORE_PREFIX = "fork_cat_store_"

    /** The richer cinematic look with every effect, heavier, off unless switched on */
    const val PREF_RICH = "fork_categories_rich"

    /** The light effects of the categories page (halo, touch, opening and the others) */
    const val PREF_FX = "fork_categories_fx"

    /**
     * The effects are on: switched on in Plus and not switched off from outside with
     * "category_effects". Without the categories there are no effects either.
     */
    fun effectsOn(context: Context?): Boolean {
        context ?: return false
        if (!available(context) || !ForkFeatures.isOn(Feature.CATEGORY_EFFECTS, context)) return false
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_FX, true)
    }

    /** Badges for dubbing and subtitles on the posters */
    const val PREF_BADGES = "fork_categories_badges"

    /** Results gathered lately, by kind, keywords and page */
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Pair<String, List<SearchResponse>>>>>()

    fun badgesShown(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_BADGES, true)

    private val dubWords = Regex("dublaj|dubbed|türkçe ses|turkce ses|tr dub", RegexOption.IGNORE_CASE)
    private val subWords = Regex("altyaz|subbed|\\bsub\\b", RegexOption.IGNORE_CASE)

    /** "TR Dublaj", "Altyazılı" or nothing, from what the repository tells about an item. */
    fun dubBadge(card: SearchResponse): String {
        val status = (card as? com.lagradost.cloudstream3.AnimeSearchResponse)?.dubStatus
        val name = card.name
        return when {
            status?.contains(com.lagradost.cloudstream3.DubStatus.Dubbed) == true || dubWords.containsMatchIn(name) -> "TR Dublaj"
            status?.contains(com.lagradost.cloudstream3.DubStatus.Subbed) == true || subWords.containsMatchIn(name) -> "Altyazılı"
            else -> ""
        }
    }

    // ---------------------------------------------------------------- the developer's categories

    /**
     * Categories the developer gives everyone, read from [REMOTE_URL] when the app starts:
     *
     *   { "settings": { "enabled": true, "user_can_hide": true, "pinned_override": true },
     *     "categories": [ { "id": "ramazan", "name": "...", "kind": "genre", "keys": "...",
     *                       "scene": "Dram", "grade": 2, "icon": "local", "pinned": true,
     *                       "active": true, "until": "2027-04-01" } ] }
     *
     * Without the document nothing changes. The page does the merging, this only keeps the text.
     */
    private const val REMOTE_URL = "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstreamcatagory.json"
    private const val REMOTE_PREFS = "fork_remote_categories"

    fun remoteDocument(context: Context): String? =
        context.applicationContext.getSharedPreferences(REMOTE_PREFS, Context.MODE_PRIVATE).getString("doc", null)

    /** Reads the document again, what could not be read leaves the last one in place. */
    suspend fun refreshRemote(context: Context) {
        try {
            val response = com.lagradost.cloudstream3.app.get(REMOTE_URL)
            if (response.code !in 200..299) return
            val text = response.text.trim()
            context.applicationContext.getSharedPreferences(REMOTE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("doc", if (text == "null") "" else text).apply()
        } catch (t: Throwable) {
            // Offline, the last document stays
        }
    }

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.CATEGORIES, context)

    fun iconShown(context: Context?): Boolean {
        context ?: return false
        if (!available(context)) return false
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ICON, true)
    }

    fun animated(context: Context): Boolean {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
        return allowed && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ANIM, true)
    }

    /** Opens the categories screen. */
    fun show(activity: FragmentActivity, effects: Boolean = false) {
        if (!available(activity)) return
        val bridge = Bridge(activity)
        bridge.startWithEffects = effects
        val handle = AssetPage.show(activity, PAGE, bridges = mapOf("Cats" to bridge)) ?: return
        bridge.handle = handle
    }

    // ---------------------------------------------------------------- matching

    enum class Kind { MOVIE, SERIES, ANIME, NEW, GENRE }

    private val locale = Locale.forLanguageTag("tr")

    /** Lower case, "ı" and "i" alike, dashes as spaces, so "Bilim-Kurgu" finds "bilim kurgu". */
    fun normalize(text: String): String = text.lowercase(locale)
        .replace('ı', 'i')
        .replace('-', ' ')
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private val sectionWords = mapOf(
        Kind.MOVIE to listOf("film", "movie", "sinema"),
        Kind.SERIES to listOf("dizi", "series", "show", "tv"),
        Kind.ANIME to listOf("anime", "animasyon", "cizgi", "çizgi", "cartoon"),
        Kind.NEW to listOf("yeni", "son eklenen", "son yuklenen", "son yüklenen", "latest", "new", "guncel", "güncel", "vizyon", "recent"),
    )

    private fun typeFits(kind: Kind, type: TvType?): Boolean = when (kind) {
        Kind.MOVIE -> type == TvType.Movie || type == TvType.AnimeMovie || type == TvType.Documentary || type == TvType.Torrent
        Kind.SERIES -> type == TvType.TvSeries || type == TvType.AsianDrama || type == TvType.Documentary
        Kind.ANIME -> type == TvType.Anime || type == TvType.AnimeMovie || type == TvType.OVA || type == TvType.Cartoon
        else -> true
    }

    /** The sections of [api] that belong to the category, and if the items have to be sorted by type. */
    private fun sectionsFor(api: MainAPI, kind: Kind, keys: List<String>): Pair<List<Int>, Boolean> {
        val words = if (kind == Kind.GENRE) keys else (sectionWords[kind].orEmpty() + keys)
        val named = api.mainPage.withIndex()
            .filter { (_, data) ->
                val name = normalize(data.name)
                words.any { word -> word.isNotBlank() && name.contains(normalize(word)) }
            }
            .map { it.index }
            .take(SECTIONS_PER_SOURCE)
        if (named.isNotEmpty()) return named to false
        // Without a matching section the first sections are sorted by the type of their items
        return if (kind == Kind.MOVIE || kind == Kind.SERIES || kind == Kind.ANIME) {
            api.mainPage.indices.take(SECTIONS_PER_SOURCE).toList() to true
        } else {
            emptyList<Int>() to false
        }
    }

    // ---------------------------------------------------------------- the page's calls

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private val cards = ConcurrentHashMap<String, SearchResponse>()
        private val counter = AtomicInteger()

        private fun send(script: String) {
            handle?.runScript(script)
        }

        @JavascriptInterface
        fun animated(): Boolean = animated(activity)

        /** Opened from Plus to choose the effects: the page opens its effects panel once */
        @Volatile
        var startWithEffects = false

        /** The light effects are switched on */
        @JavascriptInterface
        fun effects(): Boolean = effectsOn(activity)

        @JavascriptInterface
        fun startEffects(): Boolean {
            val start = startWithEffects
            startWithEffects = false
            return start
        }

        /** The cinematic mode is switched on in Plus */
        @JavascriptInterface
        fun rich(): Boolean = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_RICH, false)

        /** The page is used with a remote */
        @JavascriptInterface
        fun isTv(): Boolean = isLayout(TV or EMULATOR)

        @JavascriptInterface
        fun cancel() {
            job?.cancel()
        }

        /** The badges on the posters (dubbing, subtitles) are shown */
        @JavascriptInterface
        fun badges(): Boolean = badgesShown(activity)

        /** The categories the developer gives everyone, the document as it was read last. */
        @JavascriptInterface
        fun remote(): String = remoteDocument(activity) ?: ""

        /**
         * What the page keeps (the user's categories and choices). It lives in the settings of
         * the app, so the backup of the app takes it along.
         */
        @JavascriptInterface
        fun getStore(key: String?): String? {
            key ?: return null
            return PreferenceManager.getDefaultSharedPreferences(activity).getString(STORE_PREFIX + key, null)
        }

        @JavascriptInterface
        fun setStore(key: String?, value: String?) {
            key ?: return
            PreferenceManager.getDefaultSharedPreferences(activity).edit().apply {
                if (value == null) remove(STORE_PREFIX + key) else putString(STORE_PREFIX + key, value)
            }.apply()
        }

        /**
         * Gathers a category. [spec] is { "kind": "movie|series|anime|new|genre", "keys": "a, b" }.
         * Answers with window.onCatStart(token, sources), window.onCatResults(token, {source, items})
         * for every repository that found something, and window.onCatDone(token, fromCache).
         * What was gathered is kept for a while, opening the same category again is then at once.
         */
        @JavascriptInterface
        fun load(token: Int, spec: String?, page: Int) = gatherCategory(token, spec, page, false)

        /** Like [load], without the kept results. */
        @JavascriptInterface
        fun reload(token: Int, spec: String?, page: Int) = gatherCategory(token, spec, page, true)

        private fun itemOf(card: SearchResponse, badges: Boolean): JSONObject {
            val id = counter.incrementAndGet().toString()
            cards[id] = card
            return JSONObject()
                .put("id", id)
                .put("name", card.name)
                .put("poster", card.posterUrl ?: "")
                .put("type", card.type?.name ?: "")
                .put("quality", card.quality?.name ?: "")
                .put("dub", if (badges) dubBadge(card) else "")
        }

        private fun emit(token: Int, source: String, found: List<SearchResponse>, badges: Boolean) {
            val items = JSONArray()
            for (card in found) items.put(itemOf(card, badges))
            val payload = JSONObject().put("source", source).put("items", items).toString()
            send("window.onCatResults && window.onCatResults($token, ${JSONObject.quote(payload)})")
        }

        private fun gatherCategory(token: Int, spec: String?, page: Int, fresh: Boolean) {
            val json = try {
                JSONObject(spec ?: "{}")
            } catch (t: Throwable) {
                JSONObject()
            }
            val kind = when (json.optString("kind")) {
                "movie" -> Kind.MOVIE
                "series" -> Kind.SERIES
                "anime" -> Kind.ANIME
                "new" -> Kind.NEW
                else -> Kind.GENRE
            }
            val keys = json.optString("keys").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val cacheKey = "${kind.name}|${keys.joinToString(",") { normalize(it) }}|$page"
            val badges = badgesShown(activity)
            job?.cancel()
            job = scope.launch {
                val kept = cache[cacheKey]
                if (!fresh && kept != null && System.currentTimeMillis() - kept.first < CACHE_MS) {
                    send("window.onCatStart && window.onCatStart($token, ${kept.second.size})")
                    for ((source, found) in kept.second) emit(token, source, found, badges)
                    send("window.onCatDone && window.onCatDone($token, true)")
                    return@launch
                }
                val providers = try {
                    activity.filterProviderByPreferredMedia(hasHomePageIsRequired = true)
                } catch (t: Throwable) {
                    logError(t)
                    emptyList()
                }
                val work = providers.mapNotNull { api ->
                    val (sections, byType) = sectionsFor(api, kind, keys)
                    if (sections.isEmpty()) null else Triple(api, sections, byType)
                }
                send("window.onCatStart && window.onCatStart($token, ${work.size})")
                val gate = Semaphore(PARALLEL)
                val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, List<SearchResponse>>>())
                work.map { (api, sections, byType) ->
                    async {
                        gate.withPermit {
                            val found = withTimeoutOrNull(TIMEOUT_MS) { gather(api, sections, byType, kind, page) }.orEmpty()
                            if (found.isNotEmpty()) results.add(api.name to found)
                            emit(token, api.name, found, badges)
                        }
                    }
                }.awaitAll()
                if (results.isNotEmpty()) {
                    cache[cacheKey] = System.currentTimeMillis() to results.toList()
                    // Old entries go, so the kept results stay small
                    if (cache.size > CACHE_ENTRIES) {
                        cache.entries.sortedBy { it.value.first }.take(cache.size - CACHE_ENTRIES)
                            .forEach { cache.remove(it.key) }
                    }
                }
                send("window.onCatDone && window.onCatDone($token, false)")
            }
        }

        private suspend fun gather(
            api: MainAPI,
            sections: List<Int>,
            byType: Boolean,
            kind: Kind,
            page: Int
        ): List<SearchResponse> {
            val repo = APIRepository(api)
            val seen = HashSet<String>()
            val out = ArrayList<SearchResponse>()
            for (index in sections) {
                val result = try {
                    repo.getMainPage(page.coerceAtLeast(1), index)
                } catch (t: Throwable) {
                    logError(t)
                    continue
                }
                val pages = (result as? Resource.Success)?.value ?: continue
                for (response in pages) {
                    for (list in response?.items.orEmpty()) {
                        for (card in list.list) {
                            if (byType && !typeFits(kind, card.type)) continue
                            if (seen.add(normalize(card.name))) out.add(card)
                        }
                    }
                }
            }
            return out
        }

        /** Opens one of the found items, the categories come back when the user returns. */
        @JavascriptInterface
        fun open(id: String?) {
            val card = cards[id ?: return] ?: return
            activity.runOnUiThread {
                val page = handle ?: return@runOnUiThread
                val nav = navController(activity)
                val from = nav?.currentDestination?.id
                page.hide()
                if (nav != null && from != null) {
                    val listener = object : NavController.OnDestinationChangedListener {
                        var left = false
                        override fun onDestinationChanged(
                            controller: NavController,
                            destination: androidx.navigation.NavDestination,
                            arguments: android.os.Bundle?
                        ) {
                            if (destination.id != from) {
                                left = true
                            } else if (left) {
                                controller.removeOnDestinationChangedListener(this)
                                page.reveal()
                            }
                        }
                    }
                    nav.addOnDestinationChangedListener(listener)
                }
                activity.loadSearchResult(card)
            }
        }

        @JavascriptInterface
        fun close() {
            scope.cancel()
            handle?.close()
        }
    }

    private fun navController(activity: FragmentActivity): NavController? = try {
        (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
    } catch (t: Throwable) {
        logError(t)
        null
    }
}
