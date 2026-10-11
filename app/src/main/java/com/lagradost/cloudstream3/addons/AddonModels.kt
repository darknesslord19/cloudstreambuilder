package com.lagradost.cloudstream3.addons

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The kinds of third party add-on formats that can be added from the "Add repository" dialog,
 * next to the regular CloudStream (.cs3) repositories.
 */
enum class AddonKind(val id: String, val label: String) {
    /** A Stremio add-on, identified by the url of its manifest.json */
    STREMIO("stremio", "Stremio"),

    /** A Nuvio plugin repository, a manifest.json that lists JavaScript scrapers */
    NUVIO("nuvio", "Nuvio"),

    /** A SkyStream repository, a repo.json that lists JavaScript plugins */
    SKYSTREAM("skystream", "SkyStream");

    companion object {
        fun fromId(id: String?): AddonKind? = values().firstOrNull { it.id == id }
    }
}

/**
 * One add-on source the user has added. This is what gets saved, everything else
 * (manifests, scripts) is cached on disk and can be downloaded again from [url].
 *
 * @property url the manifest/repository url, also the unique key of the source.
 * @property enabled ids of the scrapers/plugins of this source the user has turned on.
 * Unused for Stremio add-ons, as those are a single unit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AddonSource(
    @JsonProperty("kind") val kind: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("enabled") val enabled: List<String> = emptyList(),
) {
    @get:JsonIgnore
    val addonKind: AddonKind? get() = AddonKind.fromId(kind)
}

/** A single entry (scraper or plugin) inside a Nuvio or SkyStream repository. */
data class AddonEntry(
    val id: String,
    val name: String,
    val description: String?,
    /** If the repository itself marks the entry as usable */
    val enabledByDefault: Boolean,
)

/**
 * Everything needed to ask stream sources for links to one movie or episode.
 * Stored as the `data` of a movie or episode, so it must stay small and serializable.
 *
 * @property type the Stremio content type, e.g. "movie" or "series".
 * @property id the Stremio video id, e.g. "tt0111161" or "tt0903747:1:2".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaRef(
    @JsonProperty("type") val type: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("imdb") val imdb: String? = null,
    @JsonProperty("tmdb") val tmdb: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

open class AddonException(message: String) : Exception(message)

/** A plugin did not answer within the time it was given. */
class AddonTimeoutException(message: String) : AddonException(message)
