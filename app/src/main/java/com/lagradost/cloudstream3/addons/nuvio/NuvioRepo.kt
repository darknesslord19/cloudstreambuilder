package com.lagradost.cloudstream3.addons.nuvio

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.addons.AddonEntry
import com.lagradost.cloudstream3.addons.AddonHttp
import com.lagradost.cloudstream3.addons.bool
import com.lagradost.cloudstream3.addons.js.AddonJsEngine
import com.lagradost.cloudstream3.addons.js.JsPlugin
import com.lagradost.cloudstream3.addons.list
import com.lagradost.cloudstream3.addons.strings
import com.lagradost.cloudstream3.addons.text

/**
 * One scraper of a Nuvio plugin repository that is installed and switched on.
 * A scraper is a JavaScript file with a single function,
 * `getStreams(tmdbId, mediaType, season, episode)`, that finds links for a title.
 */
class NuvioScraper(
    val id: String,
    val name: String,
    private val supportedTypes: List<String>,
    private val plugin: JsPlugin,
    private val engine: () -> AddonJsEngine,
) {
    val pluginKey: String get() = plugin.key

    /** @param mediaType "movie" or "tv". */
    fun supports(mediaType: String): Boolean {
        if (supportedTypes.isEmpty()) return true
        return supportedTypes.any { normalizeType(it) == mediaType }
    }

    suspend fun getStreams(
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long,
    ): List<JsonNode> {
        val result = engine().call(plugin, "getStreams", listOf(tmdbId, mediaType, season, episode), timeoutMs)
        return if (result != null && result.isArray) result.toList() else emptyList()
    }

    private fun normalizeType(type: String): String {
        return when (val lower = type.lowercase()) {
            "series", "show", "other", "tv" -> "tv"
            else -> lower
        }
    }
}

/** Reading of the manifest.json of a Nuvio plugin repository. */
internal object NuvioRepo {

    data class ScraperInfo(
        val id: String,
        val name: String,
        val description: String?,
        val version: String,
        val filename: String,
        val supportedTypes: List<String>,
        val enabledByDefault: Boolean,
    ) {
        fun toEntry() = AddonEntry(id, name, description, enabledByDefault)
    }

    /** If [json] looks like the manifest of a Nuvio plugin repository. */
    fun isManifest(json: JsonNode?): Boolean {
        if (json == null || !json.isObject) return false
        val scrapers = json.get("scrapers") ?: return false
        return scrapers.isArray
    }

    fun scrapers(manifest: JsonNode?): List<ScraperInfo> {
        val seen = HashSet<String>()
        return manifest.list("scrapers").mapNotNull { node ->
            val id = node.text("id") ?: return@mapNotNull null
            val filename = node.text("filename") ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            // A scraper can be marked as not meant for Android phones and TVs
            val disabledPlatforms = node.strings("disabledPlatforms").map { it.lowercase() }
            val supportedPlatforms = node.strings("supportedPlatforms").map { it.lowercase() }
            val platformOk = !disabledPlatforms.contains("android") &&
                    (supportedPlatforms.isEmpty() || supportedPlatforms.any { it == "android" || it == "tv" })
            ScraperInfo(
                id = id,
                name = node.text("name") ?: id,
                description = node.text("description"),
                version = node.text("version") ?: "0",
                filename = filename,
                supportedTypes = node.strings("supportedTypes"),
                enabledByDefault = node.bool("enabled") != false && platformOk,
            )
        }
    }

    /** Url of the JavaScript file of [scraper], which is given relative to the manifest. */
    fun scriptUrl(manifestUrl: String, scraper: ScraperInfo): String {
        return AddonHttp.resolveSibling(manifestUrl, scraper.filename)
    }
}
