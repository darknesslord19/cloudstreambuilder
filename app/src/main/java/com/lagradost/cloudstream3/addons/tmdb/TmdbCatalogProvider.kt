package com.lagradost.cloudstream3.addons.tmdb

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.addons.MediaRef
import com.lagradost.cloudstream3.addons.StreamHub
import com.lagradost.cloudstream3.addons.double
import com.lagradost.cloudstream3.addons.int
import com.lagradost.cloudstream3.addons.list
import com.lagradost.cloudstream3.addons.obj
import com.lagradost.cloudstream3.addons.parseJsonTree
import com.lagradost.cloudstream3.addons.text
import com.lagradost.cloudstream3.addons.toJsonText
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException

/**
 * The catalog that comes with the stream sources: stream sources (Nuvio scrapers, Stremio
 * add-ons with streams) only find links for a title, this provider is where the titles are
 * browsed. It reads The Movie Database in the language of the app, so names, descriptions,
 * posters and episode titles are localized. Links come from [StreamHub].
 */
class TmdbCatalogProvider(
    providerName: String,
    providerLanguage: String,
    private val uiLanguage: String,
) : MainAPI() {
    override var name = providerName
    override var mainUrl = SITE
    override var lang = providerLanguage
    override var canBeOverridden = false
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private class Row(val path: String, val english: String, val turkish: String, val turkishOnly: Boolean = false)

    private val isTurkish = uiLanguage == "tr"

    /** What TMDB is asked for, a language with the region its release dates are taken from. */
    private val languageTag: String = when (uiLanguage) {
        "tr" -> "tr-TR"
        "en", "" -> "en-US"
        else -> uiLanguage
    }
    private val region: String = if (isTurkish) "TR" else "US"

    private val rows = listOf(
        Row("trending/movie/week", "Trending movies", "Trend Filmler"),
        Row("trending/tv/week", "Trending series", "Trend Diziler"),
        Row("movie/popular", "Popular movies", "Popüler Filmler"),
        Row("tv/popular", "Popular series", "Popüler Diziler"),
        Row("movie/now_playing", "In theatres", "Vizyondaki Filmler"),
        Row(
            "discover/movie?with_original_language=tr&sort_by=popularity.desc",
            "Turkish movies", "Yerli Filmler", turkishOnly = true
        ),
        Row(
            "discover/tv?with_original_language=tr&sort_by=popularity.desc",
            "Turkish series", "Yerli Diziler", turkishOnly = true
        ),
        Row("movie/top_rated", "Top rated movies", "En Çok Beğenilen Filmler"),
        Row("tv/top_rated", "Top rated series", "En Çok Beğenilen Diziler"),
        Row("movie/upcoming", "Coming soon", "Yakında"),
    ).filter { isTurkish || !it.turkishOnly }

    override val mainPage: List<MainPageData> = rows.map { row ->
        MainPageData(name = if (isTurkish) row.turkish else row.english, data = row.path, horizontalImages = false)
    }

    private fun apiUrl(path: String, extra: String = ""): String {
        val separator = if (path.contains('?')) "&" else "?"
        return StreamHub.tmdbApiUrl + "/" + path + separator + "api_key=" + StreamHub.TMDB_API_KEY +
                "&language=" + languageTag + "&region=" + region + extra
    }

    private fun image(path: String?, size: String): String? {
        if (path.isNullOrBlank()) return null
        return IMAGES + size + path
    }

    private fun itemUrl(isMovie: Boolean, id: String) = SITE + (if (isMovie) "/movie/" else "/tv/") + id

    private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /** @param fallbackIsMovie what an item is when the list does not say it, null to skip such items. */
    private fun toSearchResponse(item: JsonNode, fallbackIsMovie: Boolean?): SearchResponse? {
        val id = item.text("id") ?: return null
        val isMovie = when (item.text("media_type")) {
            "movie" -> true
            "tv" -> false
            null -> fallbackIsMovie ?: return null
            else -> return null // people
        }
        val title = item.text("title", "name", "original_title", "original_name") ?: return null
        val poster = image(item.text("poster_path"), "w342")
        val year = yearOf(item.text("release_date", "first_air_date"))
        val rating = item.double("vote_average")?.takeIf { it > 0 }?.let { Score.from10(it) }
        return if (isMovie) {
            newMovieSearchResponse(title, itemUrl(true, id), TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = year
                this.score = rating
            }
        } else {
            newTvSeriesSearchResponse(title, itemUrl(false, id), TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = year
                this.score = rating
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.data
        val json = AddonHttp.getJsonOrNull(apiUrl(path, "&page=$page"))
        val isMovie = path.startsWith("movie/") || path.startsWith("discover/movie") || path.startsWith("trending/movie")
        val items = json.list("results").mapNotNull { toSearchResponse(it, isMovie) }
        val totalPages = json.int("total_pages") ?: page
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty() && page < totalPages,
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val json = AddonHttp.getJsonOrNull(
            apiUrl("search/multi", "&include_adult=false&page=1&query=" + AddonHttp.encodeComponent(query))
        )
        return json.list("results").mapNotNull { toSearchResponse(it, null) }
    }

    /** The details in the app language, with a text in English where the translation has none. */
    private suspend fun details(path: String): JsonNode? {
        val json = AddonHttp.getJsonOrNull(apiUrl(path, "&append_to_response=external_ids,credits,videos,recommendations"))
            ?: return null
        return json
    }

    private suspend fun englishOverview(path: String): String? {
        if (languageTag == "en-US") return null
        val url = StreamHub.tmdbApiUrl + "/" + path + "?api_key=" + StreamHub.TMDB_API_KEY + "&language=en-US"
        return AddonHttp.getJsonOrNull(url).text("overview")
    }

    private suspend fun LoadResponse.applyDetails(json: JsonNode, path: String, imdbId: String?, tmdbId: String) {
        posterUrl = image(json.text("poster_path"), "w500")
        backgroundPosterUrl = image(json.text("backdrop_path"), "w1280")
        plot = json.text("overview") ?: englishOverview(path)
        tags = json.list("genres").mapNotNull { it.text("name") }.ifEmpty { null }
        score = json.double("vote_average")?.takeIf { it > 0 }?.let { Score.from10(it) }
        actors = json.obj("credits").list("cast").take(20).mapNotNull { person ->
            val personName = person.text("name") ?: return@mapNotNull null
            ActorData(Actor(personName, image(person.text("profile_path"), "w185")), roleString = person.text("character"))
        }.ifEmpty { null }
        recommendations = json.obj("recommendations").list("results")
            .mapNotNull { toSearchResponse(it, path.startsWith("movie/")) }
            .ifEmpty { null }
        addImdbId(imdbId)
        addTMDbId(tmdbId)
        json.obj("videos").list("results")
            .filter { it.text("site") == "YouTube" && it.text("type") in listOf("Trailer", "Teaser") }
            .sortedBy { if (it.text("type") == "Trailer") 0 else 1 }
            .mapNotNull { it.text("key") }
            .take(2)
            .forEach { addTrailer("https://www.youtube.com/watch?v=$it") }
    }

    override suspend fun load(url: String): LoadResponse? {
        val tail = url.substringAfter(SITE, "").trim('/')
        val isMovie = when (tail.substringBefore('/')) {
            "movie" -> true
            "tv" -> false
            else -> return null
        }
        val id = tail.substringAfter('/').substringBefore('/').substringBefore('-').substringBefore('?')
        if (id.isEmpty() || !id.all { it.isDigit() }) return null
        val path = (if (isMovie) "movie/" else "tv/") + id
        val json = details(path) ?: return null

        val title = json.text("title", "name", "original_title", "original_name") ?: return null
        val imdbId = (json.text("imdb_id") ?: json.obj("external_ids").text("imdb_id"))?.takeIf { it.startsWith("tt") }
        val year = yearOf(json.text("release_date", "first_air_date"))
        // Stremio add-ons know titles by their IMDb id, TMDB ones are written as "tmdb:123"
        val baseId = imdbId ?: "tmdb:$id"

        fun reference(season: Int?, episode: Int?) = MediaRef(
            type = if (isMovie) "movie" else "series",
            id = if (season != null && episode != null) "$baseId:$season:$episode" else baseId,
            imdb = imdbId, tmdb = id, season = season, episode = episode, title = title, year = year,
        ).toJsonText()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, reference(null, null)) {
                applyDetails(json, path, imdbId, id)
                this.year = year
                duration = json.int("runtime")?.takeIf { it > 0 }
            }
        }

        val seasons = json.list("seasons").mapNotNull { it.int("season_number") }.distinct().take(MAX_SEASONS)
        val episodes = seasons.amap { season ->
            try {
                AddonHttp.getJsonOrNull(apiUrl("$path/season/$season")).list("episodes").mapNotNull { episode ->
                    toEpisode(episode, season, ::reference)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
        }.flatten()
            // Specials (season 0) are listed after the regular seasons
            .sortedWith(compareBy({ if ((it.season ?: 1) <= 0) Int.MAX_VALUE else it.season ?: 1 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            applyDetails(json, path, imdbId, id)
            this.year = year
            duration = json.list("episode_run_time").firstOrNull()?.asInt()?.takeIf { it > 0 }
            showStatus = when (json.text("status")?.lowercase()) {
                "ended", "canceled", "cancelled" -> ShowStatus.Completed
                "returning series", "in production" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    private fun toEpisode(
        episode: JsonNode,
        season: Int,
        reference: (season: Int?, episode: Int?) -> String,
    ): Episode? {
        val number = episode.int("episode_number") ?: return null
        return newEpisode(
            url = reference(season, number),
            initializer = {
                this.name = episode.text("name")
                this.season = season
                this.episode = number
                this.posterUrl = image(episode.text("still_path"), "w300")
                this.description = episode.text("overview")
                this.runTime = episode.int("runtime")?.takeIf { it > 0 }
                this.score = episode.double("vote_average")?.takeIf { it > 0 }?.let { Score.from10(it) }
                addDate(episode.text("air_date"))
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
        val reference = MediaRef(
            type = json.text("type") ?: return false,
            id = json.text("id") ?: return false,
            imdb = json.text("imdb"),
            tmdb = json.text("tmdb"),
            season = json.int("season"),
            episode = json.int("episode"),
            title = json.text("title"),
            year = json.int("year"),
        )
        return StreamHub.loadLinks(reference, subtitleCallback, callback)
    }

    companion object {
        const val SITE = "https://www.themoviedb.org"
        private const val IMAGES = "https://image.tmdb.org/t/p/"
        private const val MAX_SEASONS = 60
    }
}
