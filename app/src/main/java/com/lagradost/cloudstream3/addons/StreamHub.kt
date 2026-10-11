package com.lagradost.cloudstream3.addons

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.addons.nuvio.NuvioScraper
import com.lagradost.cloudstream3.addons.stremio.StremioAddon
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The stream sources that are currently switched on, kept up to date by [AddonManager]. */
internal object AddonRegistry {
    @Volatile
    var stremio: List<StremioAddon> = emptyList()

    @Volatile
    var nuvio: List<NuvioScraper> = emptyList()
}

/**
 * Collects the links for one movie or episode from every stream source that is switched on:
 * the Stremio add-ons that offer streams and the Nuvio scrapers. Catalog providers call this
 * from their loadLinks, so a title found through one add-on gets the streams of all of them,
 * which is how Stremio and Nuvio work themselves.
 */
object StreamHub {
    private const val TAG = "AddonStreams"

    /** The key the app already uses for its search suggestions. */
    const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

    private const val NUVIO_TIMEOUT_MS = 60_000L

    @Volatile
    internal var tmdbApiUrl: String = "https://api.themoviedb.org/3"

    private val tmdbCache = ConcurrentHashMap<String, String>()

    /**
     * How many Nuvio scrapers run at the same time. Every running scraper has its own script
     * environment, a repository can have dozens of them, and phones have limited memory.
     */
    private val scraperSlots = Semaphore(8)

    suspend fun loadLinks(
        ref: MediaRef,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val found = AtomicBoolean(false)
        val onLink: (ExtractorLink) -> Unit = { link ->
            found.set(true)
            callback(link)
        }

        val tasks = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
        for (addon in AddonRegistry.stremio) {
            if (addon.supports("stream", ref.type, ref.id)) {
                tasks += async {
                    guarded(addon.name) {
                        for (stream in addon.streams(ref.type, ref.id)) {
                            guarded(addon.name) { emitStremioStream(addon.name, stream, subtitleCallback, onLink) }
                        }
                    }
                }
            }
            if (addon.supports("subtitles", ref.type, ref.id)) {
                tasks += async {
                    guarded(addon.name) {
                        for (subtitle in addon.subtitles(ref.type, ref.id)) {
                            emitSubtitle(subtitle, subtitleCallback)
                        }
                    }
                }
            }
        }

        val mediaType = nuvioMediaType(ref)
        val scrapers = AddonRegistry.nuvio.filter { it.supports(mediaType) }
        if (scrapers.isNotEmpty()) {
            tasks += async {
                guarded("Nuvio") {
                    val tmdbId = resolveTmdbId(ref) ?: return@guarded
                    coroutineScope {
                        scrapers.map { scraper ->
                            async {
                                guarded(scraper.name) {
                                    val results = scraperSlots.withPermit {
                                        scraper.getStreams(
                                            tmdbId, mediaType, ref.season, ref.episode, NUVIO_TIMEOUT_MS
                                        )
                                    }
                                    for (result in results) {
                                        guarded(scraper.name) {
                                            emitNuvioStream(scraper.name, result, subtitleCallback, onLink)
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }

        tasks.awaitAll()
        found.get()
    }

    /** If any installed source could give streams for this reference at all. */
    fun hasSourceFor(ref: MediaRef): Boolean {
        if (AddonRegistry.stremio.any { it.supports("stream", ref.type, ref.id) }) return true
        val mediaType = nuvioMediaType(ref)
        return AddonRegistry.nuvio.any { it.supports(mediaType) }
    }

    private suspend fun guarded(source: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // One broken source must never take the links of the others down with it
            Log.w(TAG, "$source: ${t.message}")
        }
    }

    // ------------------------------------------------------------ Stremio

    private suspend fun emitStremioStream(
        addonName: String,
        stream: JsonNode,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val hints = stream.obj("behaviorHints")
        val name = stream.text("name")
        val detail = stream.text("title", "description")
        val fileName = hints.text("filename")
        val label = linkLabel(addonName, name, detail)
        val quality = qualityOf(name, detail, fileName)
        val headers = hints.obj("proxyHeaders").stringMap("request") + stream.stringMap("headers")

        for (subtitle in stream.list("subtitles")) {
            emitSubtitle(subtitle, subtitleCallback)
        }

        val url = stream.text("url")
        val infoHash = stream.text("infoHash")
        val youtubeId = stream.text("ytId")
        val externalUrl = stream.text("externalUrl")
        when {
            url != null -> callback(buildLink(addonName, label, url, quality, headers, null))

            infoHash != null -> {
                val magnet = buildMagnet(
                    infoHash,
                    fileName ?: detail?.lineSequence()?.firstOrNull(),
                    stream.strings("sources"),
                    stream.int("fileIdx"),
                )
                callback(buildLink(addonName, label, magnet, quality, emptyMap(), ExtractorLinkType.MAGNET))
            }

            youtubeId != null ->
                loadExtractor("https://www.youtube.com/watch?v=$youtubeId", subtitleCallback, callback)

            externalUrl != null && externalUrl.startsWith("http") ->
                loadExtractor(externalUrl, subtitleCallback, callback)
        }
    }

    private suspend fun emitSubtitle(subtitle: JsonNode, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = subtitle.text("url") ?: return
        if (!url.startsWith("http")) return
        val language = subtitle.text("lang", "language", "label", "name") ?: "Unknown"
        val headers = subtitle.stringMap("headers")
        subtitleCallback(newSubtitleFile(language, url) {
            if (headers.isNotEmpty()) this.headers = headers
        })
    }

    // ------------------------------------------------------------ Nuvio

    private fun nuvioMediaType(ref: MediaRef): String {
        return when (ref.type) {
            "movie" -> "movie"
            "series" -> "tv"
            else -> if (ref.season != null || ref.episode != null) "tv" else "movie"
        }
    }

    private suspend fun emitNuvioStream(
        scraperName: String,
        result: JsonNode,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (!result.isObject) return
        // The url is normally a text, a few scrapers wrap it in an object
        val url = result.text("url") ?: result.obj("url").text("url")
        val infoHash = result.text("infoHash")
        val name = result.text("name")
        val title = result.text("title")
        val size = result.text("size")
        val qualityText = result.text("quality")
        val detail = listOfNotNull(title, size).joinToString(" ").ifBlank { null }
        val label = linkLabel(scraperName, name ?: result.text("provider"), detail)
        val quality = qualityOf(qualityText, name, title)

        for (subtitle in result.list("subtitles")) {
            emitSubtitle(subtitle, subtitleCallback)
        }

        when {
            url != null && !url.contains("[object") ->
                callback(buildLink(scraperName, label, url, quality, result.stringMap("headers"), null))

            infoHash != null -> callback(
                buildLink(
                    scraperName, label, buildMagnet(infoHash, title, emptyList(), result.int("fileIdx")),
                    quality, emptyMap(), ExtractorLinkType.MAGNET
                )
            )
        }
    }

    /** The TMDB id of [ref], looked up from its IMDb id when the catalog did not provide one. */
    suspend fun resolveTmdbId(ref: MediaRef): String? {
        ref.tmdb?.let { return it }
        val imdb = ref.imdb ?: return null
        val isMovie = nuvioMediaType(ref) == "movie"
        val cacheKey = (if (isMovie) "m:" else "t:") + imdb
        tmdbCache[cacheKey]?.let { return it.ifEmpty { null } }

        val json = AddonHttp.getJsonOrNull(
            tmdbApiUrl + "/find/" + AddonHttp.encodeComponent(imdb) +
                    "?api_key=" + TMDB_API_KEY + "&external_source=imdb_id"
        ) ?: return null
        val first = if (isMovie) "movie_results" else "tv_results"
        val second = if (isMovie) "tv_results" else "movie_results"
        val id = json.list(first).firstOrNull().text("id") ?: json.list(second).firstOrNull().text("id")
        tmdbCache[cacheKey] = id ?: ""
        return id
    }

    // ------------------------------------------------------------ shared link helpers

    internal suspend fun buildLink(
        source: String,
        label: String,
        url: String,
        quality: Int,
        headers: Map<String, String>,
        type: ExtractorLinkType?,
    ): ExtractorLink {
        val refererKey = headers.keys.firstOrNull { it.equals("referer", ignoreCase = true) }
        return newExtractorLink(source, label, url, type ?: linkTypeOf(url)) {
            this.quality = quality
            if (refererKey != null) {
                this.referer = headers[refererKey] ?: ""
                this.headers = headers - refererKey
            } else {
                this.headers = headers
            }
        }
    }

    /** What kind of link [url] is, with an optional [hint] such as "m3u8" given by the source. */
    internal fun linkTypeOf(url: String, hint: String? = null): ExtractorLinkType {
        if (url.startsWith("magnet:", ignoreCase = true)) return ExtractorLinkType.MAGNET
        when (hint?.lowercase()) {
            "m3u8", "hls" -> return ExtractorLinkType.M3U8
            "dash", "mpd" -> return ExtractorLinkType.DASH
        }
        val lower = url.lowercase()
        val path = lower.substringBefore('#').substringBefore('?')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".torrent") -> ExtractorLinkType.TORRENT
            // Playlists are often served through a proxy url that carries the real one as a parameter
            lower.contains(".m3u8") -> ExtractorLinkType.M3U8
            lower.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private val qualityPatterns = listOf(
        Regex("""(?<![a-z0-9])(2160p?|4k|uhd)(?![a-z0-9])""") to Qualities.P2160.value,
        Regex("""(?<![a-z0-9])(1440p?|2k)(?![a-z0-9])""") to Qualities.P1440.value,
        Regex("""(?<![a-z0-9])(1080[pi]?|fhd)(?![a-z0-9])""") to Qualities.P1080.value,
        Regex("""(?<![a-z0-9])720p?(?![a-z0-9])""") to Qualities.P720.value,
        Regex("""(?<![a-z0-9])480p?(?![a-z0-9])""") to Qualities.P480.value,
        Regex("""(?<![a-z0-9])360p?(?![a-z0-9])""") to Qualities.P360.value,
        Regex("""(?<![a-z0-9])240p?(?![a-z0-9])""") to Qualities.P240.value,
        Regex("""(?<![a-z0-9])144p?(?![a-z0-9])""") to Qualities.P144.value,
    )

    /** The video quality named in the first of [texts] that names one. */
    internal fun qualityOf(vararg texts: String?): Int {
        for (text in texts) {
            val lower = text?.lowercase() ?: continue
            for ((pattern, quality) in qualityPatterns) {
                if (pattern.containsMatchIn(lower)) return quality
            }
        }
        return Qualities.Unknown.value
    }

    /** One line that tells the user which source a link is from and what it is. */
    internal fun linkLabel(source: String, name: String?, detail: String?): String {
        val first = name?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
        val second = detail?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(2)
            ?.joinToString(" ")
        val parts = listOfNotNull(first?.takeIf { it.isNotEmpty() }, second?.takeIf { it.isNotEmpty() })
        var text = parts.joinToString(" | ")
        if (text.isEmpty()) {
            text = source
        } else if (!text.contains(source, ignoreCase = true)) {
            text = "$source | $text"
        }
        return if (text.length > 160) text.substring(0, 160) else text
    }

    internal fun buildMagnet(
        infoHash: String,
        displayName: String?,
        sources: List<String>,
        fileIndex: Int?,
    ): String {
        val magnet = StringBuilder("magnet:?xt=urn:btih:").append(infoHash.trim())
        if (!displayName.isNullOrBlank()) {
            magnet.append("&dn=").append(AddonHttp.encodeComponent(displayName.trim()))
        }
        for (source in sources) {
            if (source.startsWith("tracker:")) {
                magnet.append("&tr=").append(AddonHttp.encodeComponent(source.removePrefix("tracker:")))
            }
        }
        // The app's torrent player reads the wanted file from this parameter, counted from 1
        if (fileIndex != null && fileIndex >= 0) {
            magnet.append("&index=").append(fileIndex + 1)
        }
        return magnet.toString()
    }
}
