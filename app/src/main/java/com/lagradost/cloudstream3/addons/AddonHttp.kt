package com.lagradost.cloudstream3.addons

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/** Small helpers for the plain downloads the add-on code does itself (manifests, scripts). */
internal object AddonHttp {
    private const val DEFAULT_TIMEOUT_SECONDS = 20L

    /** Largest script or package that will be downloaded, anything bigger is not a plugin. */
    const val MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L

    /** @throws AddonException when the server does not answer with a success code. */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): String {
        val response = app.get(url, headers = headers, timeout = timeoutSeconds)
        if (!response.isSuccessful) {
            throw AddonException("HTTP ${response.code} for $url")
        }
        return response.text
    }

    /** @return the parsed JSON, or null when the request fails or the answer is not JSON. */
    suspend fun getJsonOrNull(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): JsonNode? {
        return try {
            parseJsonTree(getText(url, headers, timeoutSeconds))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        }
    }

    /** @throws AddonException when the download fails or is larger than [MAX_DOWNLOAD_BYTES]. */
    suspend fun getBytes(url: String, timeoutSeconds: Long = 60L): ByteArray {
        val response = app.get(url, timeout = timeoutSeconds)
        if (!response.isSuccessful) {
            throw AddonException("HTTP ${response.code} for $url")
        }
        response.okhttpResponse.body.use { body ->
            val declared = body.contentLength()
            if (declared > MAX_DOWNLOAD_BYTES) {
                throw AddonException("Download is too large: $url")
            }
            val bytes = body.bytes()
            if (bytes.size > MAX_DOWNLOAD_BYTES) {
                throw AddonException("Download is too large: $url")
            }
            return bytes
        }
    }

    /** Same result as JavaScript's encodeURIComponent for the characters that matter here. */
    fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("*", "%2A")
    }

    /** Resolves [path] against the directory of [baseFileUrl], absolute urls are returned as is. */
    fun resolveSibling(baseFileUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("//")) return "https:$path"
        val withoutQuery = baseFileUrl.substringBefore('?').substringBefore('#')
        if (path.startsWith("/")) {
            val schemeEnd = withoutQuery.indexOf("://")
            if (schemeEnd > 0) {
                val hostEnd = withoutQuery.indexOf('/', schemeEnd + 3)
                val origin = if (hostEnd < 0) withoutQuery else withoutQuery.substring(0, hostEnd)
                return origin + path
            }
        }
        val directory = withoutQuery.substringBeforeLast('/', withoutQuery)
        return directory + "/" + path.removePrefix("./")
    }
}
