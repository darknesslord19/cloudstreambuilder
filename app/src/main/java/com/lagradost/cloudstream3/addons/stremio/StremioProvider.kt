package com.lagradost.cloudstream3.addons.stremio

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addons.AddonHttp
import com.lagradost.cloudstream3.addons.AddonManager
import com.lagradost.cloudstream3.addons.AddonRegistry
import com.lagradost.cloudstream3.addons.MediaRef
import com.lagradost.cloudstream3.addons.StreamHub
import com.lagradost.cloudstream3.addons.int
import com.lagradost.cloudstream3.addons.list
import com.lagradost.cloudstream3.addons.obj
import com.lagradost.cloudstream3.addons.parseJsonTree
import com.lagradost.cloudstream3.addons.strings
import com.lagradost.cloudstream3.addons.text
import com.lagradost.cloudstream3.addons.toJsonText
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows the catalogs of one Stremio add-on as a normal provider: its catalogs become the rows
 * of the home page, its search catalogs answer searches, and a title opens with the details
 * from the add-on. Links come from [StreamHub], that is from every stream source installed.
 */
class StremioProvider(
    val addon: StremioAddon,
    providerName: String,
    language: String,
) : MainAPI() {
    override var name = providerName
    override var mainUrl = addon.baseUrl
    override var lang = language
    override var canBeOverridden = false
    override val hasQuickSearch = false

    /** Catalogs that can be listed without the user typing something. */
    private val homeCatalogs = addon.catalogs.filter { fixedExtras(it, null) != null }

    /** Catalogs that take a search text. */
    private val searchCatalogs = addon.catalogs.filter {
        it.supports("search") && fixedExtras(it, "search") != null
    }

    override val hasMainPage = homeCatalogs.isNotEmpty()

    // The app tells the rows of a home page apart by their name, so every name is used once
    override val mainPage: List<MainPageData> = run {
        val used = HashSet<String>()
        homeCatalogs.map { catalog ->
            var title = catalogTitle(catalog)
            if (!used.add(title)) {
                title = title + " (" + catalog.id + ")"
                var number = 2
                while (!used.add(title)) {
                    title = catalogTitle(catalog) + " (" + catalog.id + " " + number + ")"
                    number++
                }
            }
            MainPageData(name = title, data = catalogKey(catalog), horizontalImages = false)
        }
    }

    override val supportedTypes: Set<TvType> = addon.types.flatMap { type ->
        when (type.lowercase()) {
            "movie" -> listOf(TvType.Movie)
            "series" -> listOf(TvType.TvSeries)
            "anime" -> listOf(TvType.Anime, TvType.AnimeMovie)
            "tv" -> listOf(TvType.Live)
            else -> listOf(TvType.Others)
        }
    }.toSet().ifEmpty { setOf(TvType.Movie, TvType.TvSeries) }

    /** How many items the first page of a catalog had, used to ask for the following pages. */
    private val pageSizes = ConcurrentHashMap<String, Int>()

    private fun catalogKey(catalog: StremioAddon.Catalog) = catalog.type + "|" + catalog.id

    private fun catalogTitle(catalog: StremioAddon.Catalog): String {
        val type = catalog.type.replaceFirstChar { it.uppercase() }
        return if (catalog.name.contains(type, ignoreCase = true)) catalog.name else catalog.name + " - " + type
    }

    /**
     * The extras [catalog] insists on, filled with their first option.
     * @return null when a required extra other than [allowed] cannot be filled in.
     */
    private fun fixedExtras(catalog: StremioAddon.Catalog, allowed: String?): Map<String, String>? {
        val extras = LinkedHashMap<String, String>()
        for (extra in catalog.required) {
            if (extra.name == allowed) continue
            if (extra.name == "search") return null
            extras[extra.name] = extra.options.firstOrNull() ?: return null
        }
        return extras
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalog = homeCatalogs.firstOrNull { catalogKey(it) == request.data } ?: return null
        val key = catalogKey(catalog)
        val extras = LinkedHashMap(fixedExtras(catalog, null) ?: return null)
        val canSkip = catalog.supports("skip")
        if (page > 1) {
            val pageSize = pageSizes[key]
            if (!canSkip || pageSize == null) {
                return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            }
            extras["skip"] = ((page - 1) * pageSize).toString()
        }

        val metas = addon.catalog(catalog, extras)
        if (page == 1 && metas.isNotEmpty()) pageSizes[key] = metas.size
        val items = metas.mapNotNull { toSearchResponse(it, catalog.type) }
        val landscape = metas.firstOrNull().text("posterShape") == "landscape"
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = landscape),
            hasNext = canSkip && items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (searchCatalogs.isEmpty()) return emptyList()
        val results = searchCatalogs.amap { catalog ->
            try {
                val extras = LinkedHashMap(fixedExtras(catalog, "search") ?: emptyMap())
                extras["search"] = query
                addon.catalog(catalog, extras).mapNotNull { toSearchResponse(it, catalog.type) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
        }
        return results.flatten().distinctBy { it.url }
    }

    private fun toSearchResponse(meta: JsonNode, catalogType: String): SearchResponse? {
        val id = meta.text("id") ?: return null
        val title = meta.text("name") ?: return null
        val type = meta.text("type") ?: catalogType
        val url = addon.metaUrl(type, id)
        val poster = meta.text("poster")
        val year = meta.int("year", "releaseInfo")
        val rating = Score.from10(meta.text("imdbRating"))
        return when (type.lowercase()) {
            "series" -> newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = year
                this.score = rating
            }

            "anime" -> newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
                this.posterUrl = poster
                this.year = year
                this.score = rating
            }

            "tv" -> newLiveSearchResponse(title, url, TvType.Live, fix = false) {
                this.posterUrl = poster
                this.score = rating
            }

            "movie" -> newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = year
                this.score = rating
            }

            else -> newMovieSearchResponse(title, url, TvType.Others, fix = false) {
                this.posterUrl = poster
                this.year = year
                this.score = rating
            }
        }
    }

    /** Asks this add-on, then the other installed ones, then Cinemeta for the details of a title. */
    private suspend fun fetchMeta(type: String, id: String): JsonNode? {
        val candidates = LinkedHashSet<StremioAddon>()
        if (addon.supports("meta", type, id)) candidates.add(addon)
        AddonRegistry.stremio.filterTo(candidates) { it.supports("meta", type, id) }
        for (candidate in candidates) {
            candidate.meta(type, id)?.let { return it }
        }
        if (id.startsWith("tt")) {
            val cinemeta = AddonManager.fallbackCatalogUrl.substringBefore("/manifest.json")
            AddonHttp.getJsonOrNull(
                cinemeta + "/meta/" + AddonHttp.encodeComponent(type) + "/" + AddonHttp.encodeComponent(id) + ".json"
            ).obj("meta")?.let { return it }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val tail = url.substringAfter("/meta/", "").substringBeforeLast(".json")
        if (tail.isEmpty() || !tail.contains('/')) return null
        val type = URLDecoder.decode(tail.substringBefore('/'), "UTF-8")
        val id = URLDecoder.decode(tail.substringAfter('/'), "UTF-8")

        val meta = fetchMeta(type, id)
        val title = meta.text("name") ?: id
        val imdbId = meta.text("imdb_id") ?: id.takeIf { it.startsWith("tt") }?.substringBefore(':')
        val tmdbId = meta.text("moviedb_id", "tmdb_id")
            ?: id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(':')
        val year = meta.int("year", "releaseInfo", "released")
        val videos = meta.list("videos")
        val lowerType = type.lowercase()

        fun reference(videoId: String, season: Int?, episode: Int?) = MediaRef(
            type = type, id = videoId, imdb = imdbId, tmdb = tmdbId,
            season = season, episode = episode, title = title, year = year,
        ).toJsonText()

        suspend fun LoadResponse.applyDetails() {
            posterUrl = meta.text("poster")
            backgroundPosterUrl = meta.text("background")
            logoUrl = meta.text("logo")
            plot = meta.text("description")
            this.year = year
            tags = (meta.strings("genres") + meta.strings("genre")).distinct().ifEmpty { null }
            score = Score.from10(meta.text("imdbRating"))
            addDuration(meta.text("runtime"))
            meta.strings("cast").takeIf { it.isNotEmpty() }?.let { addActors(it) }
            addImdbId(imdbId)
            addTMDbId(tmdbId)
            val trailerIds = meta.list("trailerStreams").mapNotNull { it.text("ytId") } +
                    meta.list("trailers").mapNotNull { it.text("source") }
            for (trailer in trailerIds.distinct().take(3)) {
                addTrailer("https://www.youtube.com/watch?v=$trailer")
            }
        }

        val isEpisodic = when (lowerType) {
            "series", "channel" -> videos.isNotEmpty()
            "movie", "tv" -> false
            else -> videos.size > 1 || videos.any { it.int("season") != null }
        }

        if (isEpisodic) {
            val episodes = videos.mapIndexedNotNull { index, video -> toEpisode(video, index, ::reference) }
                // Specials (season 0) are listed after the regular seasons
                .sortedWith(compareBy({ if ((it.season ?: 1) <= 0) Int.MAX_VALUE else it.season ?: 1 }, { it.episode ?: 0 }))
            val tvType = when (lowerType) {
                "series" -> TvType.TvSeries
                "anime" -> TvType.Anime
                else -> TvType.Others
            }
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                applyDetails()
                showStatus = when (meta.text("status")?.lowercase()) {
                    "ended", "canceled", "cancelled" -> ShowStatus.Completed
                    "continuing", "returning series", "in production" -> ShowStatus.Ongoing
                    else -> null
                }
            }
        }

        val videoId = meta.obj("behaviorHints").text("defaultVideoId")
            ?: videos.firstOrNull().text("id")
            ?: id
        val data = reference(videoId, null, null)
        if (lowerType == "tv") {
            return newLiveStreamLoadResponse(title, url, data) { applyDetails() }
        }
        val tvType = when (lowerType) {
            "movie" -> TvType.Movie
            "anime" -> TvType.AnimeMovie
            else -> TvType.Others
        }
        return newMovieLoadResponse(title, url, tvType, data) { applyDetails() }
    }

    private fun toEpisode(
        video: JsonNode,
        index: Int,
        reference: (videoId: String, season: Int?, episode: Int?) -> String,
    ): Episode? {
        val videoId = video.text("id") ?: return null
        val season = video.int("season")
        val number = video.int("episode", "number") ?: (index + 1)
        return newEpisode(
            url = reference(videoId, season, number),
            initializer = {
                this.name = video.text("title", "name")
                this.season = season
                this.episode = number
                this.posterUrl = video.text("thumbnail")
                this.description = video.text("overview", "description")
                addDate(video.text("released", "firstAired"))
            },
            fix = false,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val json = parseJsonTree(data) ?: return false
        val type = json.text("type") ?: return false
        val id = json.text("id") ?: return false
        val reference = MediaRef(
            type = type,
            id = id,
            imdb = json.text("imdb"),
            tmdb = json.text("tmdb"),
            season = json.int("season"),
            episode = json.int("episode"),
            title = json.text("title"),
            year = json.int("year"),
        )
        return StreamHub.loadLinks(reference, subtitleCallback, callback)
    }
}
