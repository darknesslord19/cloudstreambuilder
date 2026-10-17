package com.lagradost.cloudstream3.fork

import android.content.Context
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
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
 * TMDB genres, and every chosen genre becomes a row of its own ("Aksiyon", "Komedi" …).
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** source -> genre -> items, made for the rows the source showed */
    private val made = ConcurrentHashMap<String, Map<String, List<SearchResponse>>>()
    private val making = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var onReady: (() -> Unit)? = null

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

    /** The extra rows for [api] made from [items], made in the background the first time. */
    fun rows(context: Context, api: String?, items: List<SearchResponse>): List<HomePageList> {
        api ?: return emptyList()
        val wanted = chosen(context, api)
        if (wanted.isEmpty() || items.isEmpty() || !canWork(context)) return emptyList()
        val ready = made[api]
        if (ready != null && wanted.all { ready.containsKey(it) }) {
            return wanted.mapNotNull { name -> ready[name]?.takeIf { it.isNotEmpty() }?.let { HomePageList(name, it, false) } }
        }
        if (making.add(api)) {
            val unique = items.distinctBy { it.url }.filter { !TraktRecs.isRec(it) }
            scope.launch {
                try {
                    val gate = kotlinx.coroutines.sync.Semaphore(6)
                    val withGenres = kotlinx.coroutines.coroutineScope {
                        unique.map { card ->
                            async {
                                gate.acquire()
                                try {
                                    card to (TmdbPosters.genresOf(context, card) ?: emptySet())
                                } finally {
                                    gate.release()
                                }
                            }
                        }.awaitAll()
                    }
                    val result = HashMap<String, List<SearchResponse>>()
                    for (name in wanted) {
                        val genre = genres.firstOrNull { it.name == name } ?: continue
                        result[name] = withGenres.filter { (_, g) -> g.any { it in genre.ids } }.map { it.first }
                    }
                    made[api] = result
                    withContext(Dispatchers.Main) { onReady?.invoke() }
                } finally {
                    making.remove(api)
                }
            }
        }
        return emptyList()
    }
}
