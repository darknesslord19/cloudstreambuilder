package com.lagradost.cloudstream3.addons.skystream

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addons.AddonException
import com.lagradost.cloudstream3.addons.StreamHub
import com.lagradost.cloudstream3.addons.bool
import com.lagradost.cloudstream3.addons.double
import com.lagradost.cloudstream3.addons.int
import com.lagradost.cloudstream3.addons.js.AddonJsEngine
import com.lagradost.cloudstream3.addons.js.JsPlugin
import com.lagradost.cloudstream3.addons.list
import com.lagradost.cloudstream3.addons.obj
import com.lagradost.cloudstream3.addons.parseJsonTree
import com.lagradost.cloudstream3.addons.stringMap
import com.lagradost.cloudstream3.addons.strings
import com.lagradost.cloudstream3.addons.text
import com.lagradost.cloudstream3.addons.toJsonText
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.newDrmExtractorLink

/**
 * Runs one SkyStream plugin as a normal provider. The plugin is JavaScript with four
 * functions (getHome, search, load, loadStreams), each call is passed to the script and
 * its answer is turned into the matching CloudStream objects.
 *
 * @param manifest the plugin.json, with baseUrl and providerId already set for this instance.
 */
class SkyStreamProvider(
    private val manifest: JsonNode,
    providerName: String,
    private val plugin: JsPlugin,
    private val engine: () -> AddonJsEngine,
) : MainAPI() {
    override var name = providerName
    override var mainUrl = manifest.text("baseUrl")?.trimEnd('/')
        ?: ("https://skystream.invalid/" + (manifest.text("packageName") ?: "plugin"))
    override var lang = manifest.strings("languages").ifEmpty { manifest.strings("language") }
        .firstOrNull()?.lowercase()?.takeIf { it.length in 2..8 } ?: "en"
    override var canBeOverridden = false
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes: Set<TvType> = (manifest.strings("categories") + manifest.strings("tvTypes") +
            manifest.strings("types")).mapNotNull { category ->
        when (category.lowercase()) {
            "movie", "movies" -> TvType.Movie
            "tv", "series", "tvseries", "tvshow", "tvshows" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "animemovie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            "cartoon" -> TvType.Cartoon
            "asiandrama" -> TvType.AsianDrama
            "documentary" -> TvType.Documentary
            "livetv", "iptv", "livestream", "live" -> TvType.Live
            "nsfw" -> TvType.NSFW
            "torrent" -> TvType.Torrent
            "others", "other" -> TvType.Others
            else -> null
        }
    }.toSet().ifEmpty { setOf(TvType.Movie, TvType.TvSeries) }

    val pluginKey: String get() = plugin.key

    private suspend fun call(function: String, args: List<Any?>, timeoutMs: Long): JsonNode? {
        try {
            return engine().call(plugin, function, args, timeoutMs)
        } catch (e: AddonException) {
            throw ErrorLoadingException(e.message)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // A SkyStream plugin gives its whole home screen in one go
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val home = call("getHome", emptyList(), HOME_TIMEOUT_MS)
        if (home == null || !home.isObject) throw ErrorLoadingException("The plugin returned no home page")
        val lists = ArrayList<HomePageList>()
        val fields = home.fields()
        while (fields.hasNext()) {
            val (category, items) = fields.next()
            if (items == null || !items.isArray) continue
            val results = items.mapNotNull { toSearchResponse(it) }
            if (results.isNotEmpty()) lists.add(HomePageList(category, results))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val found = call("search", listOf(query), SEARCH_TIMEOUT_MS)
        if (found == null || !found.isArray) return emptyList()
        return found.mapNotNull { toSearchResponse(it) }
    }

    private fun tvTypeOf(item: JsonNode, hasEpisodes: Boolean): TvType {
        return when ((item.text("type", "contentType") ?: "movie").lowercase()) {
            "movie" -> TvType.Movie
            "series", "tvseries", "tv" -> TvType.TvSeries
            "anime" -> if (hasEpisodes) TvType.Anime else TvType.AnimeMovie
            "livestream", "live", "iptv" -> TvType.Live
            else -> if (hasEpisodes) TvType.TvSeries else TvType.Others
        }
    }

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        if (!item.isObject) return null
        val title = item.text("title", "name") ?: return null
        val url = wrapUrl(item.text("url") ?: return null)
        val poster = item.text("posterUrl", "poster")
        val headers = item.stringMap("headers").takeIf { it.isNotEmpty() }
        val year = item.int("year")
        val rating = Score.from10(item.double("score"))
        return when ((item.text("type", "contentType") ?: "movie").lowercase()) {
            "series", "tvseries", "tv" -> newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.year = year
                this.score = rating
            }

            "anime" -> newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.year = year
                this.score = rating
            }

            "livestream", "live", "iptv" -> newLiveSearchResponse(title, url, TvType.Live, fix = false) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.score = rating
            }

            else -> newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.year = year
                this.score = rating
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pluginUrl = unwrapUrl(url)
        val item = call("load", listOf(pluginUrl), LOAD_TIMEOUT_MS)
        if (item == null || !item.isObject) throw ErrorLoadingException("The plugin returned no details")

        val title = item.text("title", "name") ?: "Unknown"
        val episodes = item.list("episodes").filter { it.isObject }
        val itemStreams = item.list("streams")
        val typeText = (item.text("type", "contentType") ?: "movie").lowercase()
        val isEpisodic = when (typeText) {
            "movie", "livestream", "live", "iptv" -> episodes.size > 1
            else -> episodes.isNotEmpty()
        }

        suspend fun LoadResponse.applyDetails() {
            posterUrl = item.text("posterUrl", "poster")
            posterHeaders = item.stringMap("headers").takeIf { it.isNotEmpty() }
            backgroundPosterUrl = item.text("bannerUrl", "backgroundPosterUrl")
            logoUrl = item.text("logoUrl")
            plot = item.text("description")?.let { unescapeHtml(it) }
            year = item.int("year")
            score = Score.from10(item.double("score"))
            duration = item.int("duration")
            tags = item.strings("tags").ifEmpty { null }
            contentRating = item.text("contentRating")
            val cast = (item.list("cast") + item.list("actors")).mapNotNull { actor ->
                val actorName = actor.text("name") ?: return@mapNotNull null
                ActorData(Actor(actorName, actor.text("image")), roleString = actor.text("role", "roleString"))
            }
            if (cast.isNotEmpty()) actors = cast
            for (trailer in item.list("trailers").take(3)) {
                val trailerUrl = trailer.text("url", "extractorUrl") ?: continue
                addTrailer(trailerUrl, headers = trailer.stringMap("headers"))
            }
            recommendations = item.list("recommendations").mapNotNull { toSearchResponse(it) }.ifEmpty { null }
            val sync = item.stringMap("syncData")
            addImdbId(sync["imdb"] ?: sync["imdbId"] ?: sync["imdb_id"] ?: item.text("imdbId"))
            addTMDbId(sync["tmdb"] ?: sync["tmdbId"] ?: sync["tmdb_id"] ?: item.text("tmdbId"))
        }

        if (isEpisodic) {
            val list = episodes.mapIndexed { index, episode -> toEpisode(episode, index) }
            return newTvSeriesLoadResponse(title, url, tvTypeOf(item, true), list) {
                applyDetails()
                val status = item.text("status", "showStatus")?.lowercase() ?: ""
                showStatus = when {
                    status.contains("completed") -> ShowStatus.Completed
                    status.contains("ongoing") -> ShowStatus.Ongoing
                    else -> null
                }
                if (status.contains("upcoming") && list.isEmpty()) comingSoon = true
            }
        }

        val single = episodes.firstOrNull()
        val data = streamData(
            single.text("url") ?: item.text("url") ?: pluginUrl,
            single.list("streams").ifEmpty { itemStreams },
        )
        if (typeText == "livestream" || typeText == "live" || typeText == "iptv") {
            return newLiveStreamLoadResponse(title, url, data) { applyDetails() }
        }
        return newMovieLoadResponse(title, url, tvTypeOf(item, false), data) { applyDetails() }
    }

    private fun toEpisode(episode: JsonNode, index: Int): Episode {
        val data = streamData(episode.text("url") ?: "", episode.list("streams"))
        return newEpisode(
            url = data,
            initializer = {
                this.name = episode.text("name", "title")?.let { unescapeHtml(it) }
                // SkyStream uses 0 for "no season/episode number"
                this.season = episode.int("season")?.takeIf { it > 0 }
                this.episode = episode.int("episode")?.takeIf { it > 0 } ?: (index + 1)
                this.posterUrl = episode.text("posterUrl")
                this.description = episode.text("description")?.let { unescapeHtml(it) }
                this.runTime = episode.int("runtime", "duration")
                this.score = Score.from10(episode.double("rating"))
                addDate(episode.text("airDate"))
            },
            fix = false,
        )
    }

    /**
     * What is stored as the data of a movie or episode: the url the plugin wants back in
     * loadStreams, and any streams the plugin already delivered together with the details.
     */
    private fun streamData(pluginUrl: String, readyStreams: List<JsonNode>): String {
        val data = LinkedHashMap<String, Any?>()
        data["skyUrl"] = pluginUrl
        if (readyStreams.isNotEmpty()) data["streams"] = readyStreams
        return data.toJsonText()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val stored = parseJsonTree(data)
        val pluginUrl = stored.text("skyUrl") ?: data
        var streams = stored.list("streams")
        if (streams.isEmpty()) {
            val answer = call("loadStreams", listOf(pluginUrl), STREAMS_TIMEOUT_MS)
            streams = if (answer != null && answer.isArray) answer.toList() else emptyList()
        }

        var found = false
        for (stream in streams.take(MAX_STREAMS)) {
            if (!stream.isObject) continue
            try {
                if (emitStream(stream, subtitleCallback, callback)) found = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // One odd entry must not hide the other streams
            }
        }
        return found
    }

    private suspend fun emitStream(
        stream: JsonNode,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val rawUrl = stream.text("url") ?: return false
        val headers = LinkedHashMap(stream.stringMap("headers"))
        stream.text("referer")?.let { if (headers.keys.none { key -> key.equals("referer", true) }) headers["Referer"] = it }

        // SkyStream lets a plugin ask for a local proxy to add headers or to serve a playlist
        // it built itself. The player here can send headers and read inline playlists directly.
        var url = rawUrl
        var forcedType: ExtractorLinkType? = null
        when {
            rawUrl.startsWith("magic_m3u8:") -> {
                url = "data:application/vnd.apple.mpegurl;base64," + rawUrl.removePrefix("magic_m3u8:")
                forcedType = ExtractorLinkType.M3U8
            }

            rawUrl.startsWith("MAGIC_PROXY_v2") -> {
                val config = parseJsonTree(decodeBase64Text(rawUrl.removePrefix("MAGIC_PROXY_v2"))) ?: return false
                url = config.text("url") ?: return false
                config.stringMap("headers").forEach { (key, value) -> headers[key] = value }
            }

            rawUrl.startsWith("MAGIC_PROXY_v1") -> url = decodeBase64Text(rawUrl.removePrefix("MAGIC_PROXY_v1"))
            rawUrl.startsWith("MAGIC_PROXY:") -> url = decodeBase64Text(rawUrl.removePrefix("MAGIC_PROXY:"))
        }
        if (url.isBlank()) return false

        for (subtitle in stream.list("subtitles")) {
            val subtitleUrl = subtitle.text("url") ?: continue
            if (!subtitleUrl.startsWith("http")) continue
            subtitleCallback(newSubtitleFile(subtitle.text("lang", "label") ?: "Unknown", subtitleUrl) {
                if (headers.isNotEmpty()) this.headers = headers
            })
        }

        val sourceLabel = stream.text("source", "name")?.takeIf { it != "Auto" && it != "Unknown" }
        val label = StreamHub.linkLabel(name, sourceLabel, null)
        val qualityNumber = stream.get("quality")?.takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
        val quality = qualityNumber ?: StreamHub.qualityOf(stream.text("quality"), sourceLabel)
        val type = forcedType ?: StreamHub.linkTypeOf(url, stream.text("type"))

        // Protected streams: either the keys come with the stream (ClearKey), or there is a
        // licence server to ask (Widevine).
        val licenseUrl = stream.text("licenseUrl")
        var keyText = stream.text("drmKey")
        var keyIdText = stream.text("drmKid")
        if (keyText != null && keyText.count { it == ':' } == 1) {
            // Some plugins put both halves into drmKey as "kid:key"
            if (keyIdText == null) keyIdText = keyText.substringBefore(':').trim()
            keyText = keyText.substringAfter(':').trim()
        }
        val clearKey = keyText?.let { toJwkValue(it) }
        val clearKeyId = keyIdText?.let { toJwkValue(it) }
        val hasClearKey = clearKey != null && clearKeyId != null
        if (hasClearKey || licenseUrl != null) {
            val refererKey = headers.keys.firstOrNull { it.equals("referer", ignoreCase = true) }
            callback(
                newDrmExtractorLink(
                    name, label, url, type,
                    if (hasClearKey) CLEARKEY_UUID else WIDEVINE_UUID,
                ) {
                    this.quality = quality
                    this.referer = refererKey?.let { headers[it] } ?: ""
                    this.headers = if (refererKey == null) headers else headers - refererKey
                    if (hasClearKey) {
                        this.kid = clearKeyId
                        this.key = clearKey
                    } else {
                        this.licenseUrl = licenseUrl
                    }
                }
            )
            return true
        }

        callback(StreamHub.buildLink(name, label, url, quality, headers, type))
        return true
    }

    /**
     * A 16 byte key or key id, written as hex or base64, in the unpadded base64url form the
     * player wants. Null if [text] is neither.
     */
    private fun toJwkValue(text: String): String? {
        val trimmed = text.trim()
        val hex = trimmed.replace("-", "")
        val bytes: ByteArray? = if (hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } else {
            try {
                val standard = trimmed.replace('-', '+').replace('_', '/').trimEnd('=')
                base64DecodeArray(standard + "=".repeat((4 - standard.length % 4) % 4)).takeIf { it.size == 16 }
            } catch (t: Throwable) {
                null
            }
        }
        if (bytes == null) return null
        return base64Encode(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
    }

    private fun decodeBase64Text(text: String): String {
        return try {
            // base64Decode maps each byte to one character, urls are plain ASCII
            base64Decode(text.trim())
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * The app treats every url that does not start with http as a path of [mainUrl] and
     * rewrites it. Plugins may use any text as the "url" of an item, so those are wrapped in
     * a JSON object, which the app leaves alone.
     */
    private fun wrapUrl(pluginUrl: String): String {
        if (pluginUrl.startsWith("http://") || pluginUrl.startsWith("https://")) return pluginUrl
        return mapOf(WRAPPED_URL_KEY to pluginUrl).toJsonText()
    }

    private fun unwrapUrl(url: String): String {
        if (!url.startsWith("{\"$WRAPPED_URL_KEY\"")) return url
        return parseJsonTree(url).text(WRAPPED_URL_KEY) ?: url
    }

    private fun unescapeHtml(text: String): String {
        if (!text.contains('&')) return text
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    }

    companion object {
        private const val WRAPPED_URL_KEY = "skyItem"
        private const val HOME_TIMEOUT_MS = 60_000L
        private const val SEARCH_TIMEOUT_MS = 45_000L
        private const val LOAD_TIMEOUT_MS = 60_000L
        private const val STREAMS_TIMEOUT_MS = 100_000L
        private const val MAX_STREAMS = 200
    }
}
