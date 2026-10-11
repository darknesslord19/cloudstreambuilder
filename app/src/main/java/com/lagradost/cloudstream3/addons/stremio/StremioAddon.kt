package com.lagradost.cloudstream3.addons.stremio

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.addons.AddonHttp
import com.lagradost.cloudstream3.addons.bool
import com.lagradost.cloudstream3.addons.list
import com.lagradost.cloudstream3.addons.obj
import com.lagradost.cloudstream3.addons.strings
import com.lagradost.cloudstream3.addons.text

/**
 * A Stremio add-on: a manifest plus a handful of JSON endpoints next to it.
 * See https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
 *
 * @param manifestUrl url of the manifest.json, with any stremio:// scheme already replaced.
 */
class StremioAddon(val manifestUrl: String, val manifest: JsonNode) {

    /** One extra parameter a catalog accepts, like "search", "genre" or "skip". */
    data class Extra(
        val name: String,
        val isRequired: Boolean,
        val options: List<String>,
    )

    data class Catalog(
        val type: String,
        val id: String,
        val name: String,
        val extra: List<Extra>,
    ) {
        fun supports(extraName: String) = extra.any { it.name == extraName }
        val required: List<Extra> get() = extra.filter { it.isRequired }
    }

    private data class Resource(
        val name: String,
        val types: List<String>?,
        val idPrefixes: List<String>?,
    )

    /** Everything before "manifest.json", requests are made relative to this. */
    val baseUrl: String

    /** Anything that came after "manifest.json" in the url, usually nothing. */
    private val urlSuffix: String

    init {
        val marker = manifestUrl.indexOf("/manifest.json")
        if (marker >= 0) {
            baseUrl = manifestUrl.substring(0, marker)
            urlSuffix = manifestUrl.substring(marker + "/manifest.json".length)
        } else {
            baseUrl = manifestUrl.substringBefore('?').trimEnd('/')
            urlSuffix = ""
        }
    }

    val id: String = manifest.text("id") ?: baseUrl
    val name: String = manifest.text("name") ?: "Stremio"
    val description: String? = manifest.text("description")
    val logo: String? = manifest.text("logo", "icon")
    val types: List<String> = manifest.strings("types")
    private val idPrefixes: List<String>? =
        manifest.strings("idPrefixes").takeIf { it.isNotEmpty() }

    val isConfigurable: Boolean =
        manifest.obj("behaviorHints").bool("configurable") == true

    val requiresConfiguration: Boolean =
        manifest.obj("behaviorHints").bool("configurationRequired") == true

    val isAdult: Boolean = manifest.obj("behaviorHints").bool("adult") == true

    private val resources: List<Resource> = manifest.list("resources").mapNotNull { node ->
        when {
            node.isTextual -> Resource(node.asText(), null, null)
            node.isObject -> {
                val resourceName = node.text("name") ?: return@mapNotNull null
                Resource(
                    resourceName,
                    node.strings("types").takeIf { it.isNotEmpty() },
                    // An explicit empty list of prefixes is valid and means "everything"
                    if (node.has("idPrefixes")) node.strings("idPrefixes") else null,
                )
            }

            else -> null
        }
    }

    val catalogs: List<Catalog> = manifest.list("catalogs").mapNotNull { node ->
        val type = node.text("type") ?: return@mapNotNull null
        val catalogId = node.text("id") ?: return@mapNotNull null
        val extras = LinkedHashMap<String, Extra>()
        for (extra in node.list("extra")) {
            val extraName = extra.text("name") ?: continue
            extras[extraName] = Extra(
                extraName,
                extra.bool("isRequired") == true,
                extra.strings("options"),
            )
        }
        // Older manifests list the extras as two plain arrays instead
        val legacyRequired = node.strings("extraRequired")
        for (extraName in node.strings("extraSupported") + legacyRequired) {
            if (!extras.containsKey(extraName)) {
                extras[extraName] = Extra(extraName, legacyRequired.contains(extraName), emptyList())
            }
        }
        Catalog(type, catalogId, node.text("name") ?: catalogId, extras.values.toList())
    }

    fun hasResource(resource: String): Boolean = resources.any { it.name == resource }

    /** If this add-on declares it can answer [resource] requests for the given type and id. */
    fun supports(resource: String, type: String, id: String): Boolean {
        return resources.any { entry ->
            if (entry.name != resource) return@any false
            val entryTypes = entry.types ?: types
            if (entryTypes.isNotEmpty() && !entryTypes.contains(type)) return@any false
            val prefixes = entry.idPrefixes ?: idPrefixes
            prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
        }
    }

    /** Page where the user can set the add-on up, if it has one. */
    val configureUrl: String? get() = if (isConfigurable) "$baseUrl/configure" else null

    private fun resourceUrl(resource: String, type: String, id: String, extra: Map<String, String>): String {
        val extraPath = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (key, value) ->
            AddonHttp.encodeComponent(key) + "=" + AddonHttp.encodeComponent(value)
        }
        return baseUrl + "/" + resource + "/" + AddonHttp.encodeComponent(type) + "/" +
                AddonHttp.encodeComponent(id) + extraPath + ".json" + urlSuffix
    }

    fun metaUrl(type: String, id: String): String = resourceUrl("meta", type, id, emptyMap())

    suspend fun catalog(catalog: Catalog, extra: Map<String, String>): List<JsonNode> {
        val json = AddonHttp.getJsonOrNull(resourceUrl("catalog", catalog.type, catalog.id, extra))
        return json.list("metas")
    }

    suspend fun meta(type: String, id: String): JsonNode? {
        return AddonHttp.getJsonOrNull(metaUrl(type, id)).obj("meta")
    }

    suspend fun streams(type: String, videoId: String): List<JsonNode> {
        val json = AddonHttp.getJsonOrNull(
            resourceUrl("stream", type, videoId, emptyMap()),
            timeoutSeconds = STREAM_TIMEOUT_SECONDS,
        )
        return json.list("streams")
    }

    suspend fun subtitles(type: String, videoId: String): List<JsonNode> {
        val json = AddonHttp.getJsonOrNull(resourceUrl("subtitles", type, videoId, emptyMap()))
        return json.list("subtitles")
    }

    companion object {
        /** Stream add-ons often scrape on demand, so they get more time than plain metadata. */
        const val STREAM_TIMEOUT_SECONDS = 40L

        /** The official metadata add-on, used for ids no installed add-on has details for. */
        const val CINEMETA_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"

        /** Turns what a user may paste into the https url of a manifest. */
        fun normalizeManifestUrl(input: String): String {
            var url = input.trim()
            if (url.startsWith("stremio://", ignoreCase = true)) {
                url = "https://" + url.substring("stremio://".length)
            }
            return url
        }

        /** If [json] looks like the manifest of a Stremio add-on. */
        fun isManifest(json: JsonNode?): Boolean {
            if (json == null || !json.isObject) return false
            if (json.text("id") == null || json.text("name") == null) return false
            return json.has("resources") && (json.has("types") || json.has("catalogs"))
        }
    }
}
