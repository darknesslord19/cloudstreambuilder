package com.lagradost.cloudstream3.addons.js

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addons.AddonException
import com.lagradost.cloudstream3.addons.AddonTimeoutException
import com.lagradost.cloudstream3.addons.parseJsonTree
import com.lagradost.cloudstream3.addons.stringMap
import com.lagradost.cloudstream3.addons.text
import com.lagradost.cloudstream3.addons.toJsonText
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app side of the bridge that plugin JavaScript talks to (it is `window.__csHost` in
 * assets/addons/runtime.js). It gives scripts what a browser page cannot do by itself:
 * web requests without CORS limits and with any header, exact timers, and a few helpers.
 *
 * It holds no Android classes. The engine that hosts the JavaScript forwards the calls of the
 * script to the methods below, and tells the script when an asynchronous request has finished
 * through [signal].
 *
 * @param readAsset reads a bundled file from assets/addons.
 * @param readPreference reads a value a plugin stored earlier, null if there is none.
 * @param writePreference stores a value for a plugin, null removes it.
 */
class AddonHostBridge(
    private val readAsset: (String) -> String,
    private val readPreference: (String) -> String?,
    private val writePreference: (String, String?) -> Unit,
) {
    /**
     * Set by the engine. Called with the id of a finished asynchronous request, the engine
     * must then run `__cs.onNative(id)` in the page so the script picks the result up
     * with [take].
     */
    @Volatile
    var signal: ((requestId: String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val plugins = ConcurrentHashMap<String, JsPlugin>()
    private val results = ConcurrentHashMap<String, String>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val callCounter = AtomicLong(0)

    /** Plugins that are loaded in the page: key to number of running calls, least recently used first. */
    private val loaded = LinkedHashMap<String, Int>(16, 0.75f, true)

    // ------------------------------------------------------------ used by the engines

    /**
     * Runs one call of a plugin function. [evaluate] must execute the given JavaScript in
     * the page that has assets/addons/bootstrap.js loaded.
     */
    suspend fun call(
        plugin: JsPlugin,
        function: String,
        args: List<Any?>,
        timeoutMs: Long,
        evaluate: (String) -> Unit,
    ): JsonNode? {
        plugins[plugin.key] = plugin
        val callId = "c" + callCounter.incrementAndGet()
        val answer = CompletableDeferred<String>()
        calls[callId] = answer
        synchronized(loaded) { loaded[plugin.key] = (loaded[plugin.key] ?: 0) + 1 }
        try {
            evaluate(
                "__cs.invoke(" + quote(callId) + "," + quote(plugin.key) + "," +
                        quote(function) + "," + quote(args.toJsonText()) + ");"
            )
            val payload = withTimeout(timeoutMs) { answer.await() }
            return parseJsonTree(payload)
        } catch (e: TimeoutCancellationException) {
            throw AddonTimeoutException("The plugin did not answer in time")
        } finally {
            calls.remove(callId)
            release(plugin, evaluate)
        }
    }

    /** Book keeping after a call: frees scripts that should not stay in memory. */
    private fun release(plugin: JsPlugin, evaluate: (String) -> Unit) {
        val unload = ArrayList<String>()
        synchronized(loaded) {
            val running = (loaded[plugin.key] ?: 1) - 1
            if (running > 0) {
                loaded[plugin.key] = running
            } else if (!plugin.keepLoaded) {
                loaded.remove(plugin.key)
                unload.add(plugin.key)
            } else {
                loaded[plugin.key] = 0
                // Too many idle plugins: the ones that were not used for the longest go first
                val idle = loaded.entries.filter { it.value == 0 }.map { it.key }
                val excess = loaded.size - MAX_LOADED_PLUGINS
                if (excess > 0) {
                    for (key in idle.take(excess)) {
                        loaded.remove(key)
                        unload.add(key)
                    }
                }
            }
        }
        for (key in unload) {
            try {
                evaluate("__cs.unload(" + quote(key) + ");")
            } catch (t: Throwable) {
                Log.w(TAG, "Unload failed: ${t.message}")
            }
        }
    }

    fun forget(pluginKey: String) {
        plugins.remove(pluginKey)
        synchronized(loaded) { loaded.remove(pluginKey) }
    }

    /** Fails every running call and drops all pending work, used when the page is lost. */
    fun reset(reason: String) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        results.clear()
        val error = AddonException(reason)
        calls.values.forEach { it.completeExceptionally(error) }
        calls.clear()
        synchronized(loaded) { loaded.clear() }
    }

    // ------------------------------------------------------------ called by the script

    fun getAsset(name: String): String = readAsset(name)

    fun getSource(key: String): String {
        val plugin = plugins[key] ?: throw AddonException("Unknown plugin $key")
        return plugin.sourceFile.readText()
    }

    fun getEnv(key: String): String {
        val plugin = plugins[key] ?: throw AddonException("Unknown plugin $key")
        return plugin.env.toJsonText()
    }

    /** The script reports the result of a call: [ok] is "1" with a JSON payload, or "0" with a message. */
    fun done(callId: String, ok: String, payload: String) {
        val answer = calls[callId] ?: return
        if (ok == "1") {
            answer.complete(payload)
        } else {
            answer.completeExceptionally(AddonException(payload.take(MAX_ERROR_LENGTH)))
        }
    }

    fun http(requestId: String, requestJson: String) {
        start(requestId) { job -> executeHttp(job, requestJson) }
    }

    fun sleep(requestId: String, millis: String) {
        start(requestId) { _ ->
            delay((millis.toDoubleOrNull() ?: 0.0).toLong().coerceIn(0L, MAX_SLEEP_MS))
            ""
        }
    }

    /** Resolves a video host page with the extractors built into the app. */
    fun extract(requestId: String, url: String, referer: String) {
        start(requestId) { _ ->
            val links = java.util.Collections.synchronizedList(ArrayList<ExtractorLink>())
            try {
                withTimeout(EXTRACT_TIMEOUT_MS) {
                    loadExtractor(url, referer.ifBlank { null }, { }, { link -> links.add(link) })
                }
            } catch (e: TimeoutCancellationException) {
                // Keep whatever was found before the time ran out
            }
            val found = synchronized(links) { links.toList() }
            envelope(found.map { link ->
                mapOf(
                    "source" to link.source,
                    "name" to link.name,
                    "url" to link.url,
                    "referer" to link.referer,
                    "quality" to link.quality,
                    "type" to when (link.type) {
                        ExtractorLinkType.M3U8 -> "m3u8"
                        ExtractorLinkType.DASH -> "dash"
                        else -> "video"
                    },
                    "headers" to link.headers,
                )
            })
        }
    }

    /** Hands over the result of a finished request, each result can be taken once. */
    fun take(requestId: String): String = results.remove(requestId) ?: ""

    fun cancel(requestId: String) {
        jobs.remove(requestId)?.cancel()
        results.remove(requestId)
    }

    fun log(level: String, tag: String, text: String) {
        val logTag = "Addon:" + tag.take(60)
        when (level) {
            "e" -> Log.e(logTag, text)
            "w" -> Log.w(logTag, text)
            "i" -> Log.i(logTag, text)
            else -> Log.d(logTag, text)
        }
    }

    /** Unpacks "p,a,c,k,e,d" JavaScript, returns the input if it is not packed. */
    fun unpack(code: String): String {
        return try {
            getAndUnpack(code)
        } catch (t: Throwable) {
            code
        }
    }

    fun crypto(operation: String, a: String, b: String, c: String, d: String): String {
        return try {
            val value: String = when (operation) {
                "md5" -> digest("MD5", a)
                "sha1" -> digest("SHA-1", a)
                "sha256" -> digest("SHA-256", a)
                "sha512" -> digest("SHA-512", a)
                "aes-decrypt" -> aesDecrypt(a, b, c, d)
                "pbkdf2" -> pbkdf2(a, b, c.toDoubleOrNull()?.toInt() ?: 10000, d.toDoubleOrNull()?.toInt() ?: 32)
                else -> return failure("Unknown operation $operation")
            }
            envelope(value)
        } catch (t: Throwable) {
            failure(t.message ?: t.javaClass.simpleName)
        }
    }

    fun prefGet(namespace: String, key: String): String {
        return try {
            envelope(readPreference(preferenceKey(namespace, key)))
        } catch (t: Throwable) {
            failure(t.message ?: "Could not read the value")
        }
    }

    /** @param hasValue "1" to store [value], anything else removes the stored value. */
    fun prefSet(namespace: String, key: String, hasValue: String, value: String) {
        try {
            writePreference(preferenceKey(namespace, key), if (hasValue == "1") value else null)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not store a plugin value: ${t.message}")
        }
    }

    // ------------------------------------------------------------ internals

    private fun preferenceKey(namespace: String, key: String) = "addon_pref/$namespace/$key"

    private fun start(requestId: String, work: suspend (Job) -> String) {
        val job = scope.launch {
            val result = try {
                work(coroutineContext.job)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure(t.message ?: t.javaClass.simpleName)
            }
            results[requestId] = result
            jobs.remove(requestId)
            signal?.invoke(requestId)
        }
        jobs[requestId] = job
        // The work may already be done, in which case the entry above would stay forever
        if (job.isCompleted) jobs.remove(requestId)
    }

    private fun executeHttp(job: Job, requestJson: String): String {
        val request = parseJsonTree(requestJson) ?: return failure("Invalid request")
        val url = request.text("url") ?: return failure("The request has no url")
        val method = (request.text("method") ?: "GET").uppercase()
        val followRedirects = request.text("redirect") != "manual"

        val headers = Headers.Builder()
        var hasUserAgent = false
        for ((name, value) in request.stringMap("headers")) {
            val lower = name.trim().lowercase()
            // These are managed by the http client itself. A manual Accept-Encoding would also
            // switch off its transparent decompression.
            if (lower in MANAGED_HEADERS) continue
            if (lower == "user-agent") hasUserAgent = true
            try {
                headers.addUnsafeNonAscii(name.trim(), value)
            } catch (e: IllegalArgumentException) {
                // Not a valid header name, skip it like a browser would refuse it
            }
        }
        if (!hasUserAgent) headers.add("User-Agent", USER_AGENT)

        val bodyNode = request.get("body")
        val bodyBytes: ByteArray? = if (bodyNode == null || bodyNode.isNull) {
            null
        } else if (request.get("binary")?.asBoolean() == true) {
            base64DecodeArray(bodyNode.asText())
        } else {
            bodyNode.asText().toByteArray(Charsets.UTF_8)
        }
        // No media type here on purpose: the Content-Type header of the script is sent as is,
        // a media type would make the client append a charset to it.
        val requestBody = when {
            method == "GET" || method == "HEAD" -> null
            bodyBytes != null -> bodyBytes.toRequestBody(null)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val okRequest = try {
            Request.Builder().url(url).headers(headers.build()).method(method, requestBody).build()
        } catch (e: IllegalArgumentException) {
            return failure("Invalid request: ${e.message}")
        }

        val client = app.baseClient.newBuilder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .callTimeout(HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(okRequest)
        // Lets cancel() from the script, or a reset, abort a request that is still running
        val cancelHook = job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }

        try {
            call.execute().use { response ->
                val body = response.body
                val bytes = readLimited(body.byteStream(), MAX_RESPONSE_BYTES)
                val charset = try {
                    body.contentType()?.charset()?.name()
                } catch (t: Throwable) {
                    null
                }

                val responseHeaders = LinkedHashMap<String, String>()
                val setCookie = ArrayList<String>()
                for ((name, value) in response.headers) {
                    val lower = name.lowercase()
                    if (lower == "set-cookie") {
                        setCookie.add(value)
                        continue
                    }
                    val existing = responseHeaders[lower]
                    responseHeaders[lower] = if (existing == null) value else "$existing, $value"
                }

                return mapOf(
                    "ok" to true,
                    "status" to response.code,
                    "statusText" to response.message,
                    "url" to response.request.url.toString(),
                    "headers" to responseHeaders,
                    "setCookie" to setCookie,
                    "b64" to base64Encode(bytes),
                    "charset" to (charset ?: "utf-8"),
                ).toJsonText()
            }
        } finally {
            cancelHook.dispose()
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var remaining = limit
        stream.use { input ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        return output.toByteArray()
    }

    private fun digest(algorithm: String, text: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Key, iv and data are base64, the result is the decrypted text. */
    private fun aesDecrypt(dataBase64: String, keyBase64: String, ivBase64: String, mode: String): String {
        val key = SecretKeySpec(base64DecodeArray(keyBase64), "AES")
        val iv = base64DecodeArray(ivBase64)
        val data = base64DecodeArray(dataBase64)
        val cipher = if (mode.lowercase() == "gcm") {
            // The 16 byte authentication tag is expected at the end of the data
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        } else {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            }
        }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    /**
     * PBKDF2 with HMAC-SHA256, written out because the platform only offers it from
     * Android 8. The salt is base64, the result is the base64 of the derived key.
     */
    private fun pbkdf2(password: String, saltBase64: String, iterations: Int, keyLength: Int): String {
        require(iterations in 1..MAX_PBKDF2_ITERATIONS) { "Unsupported iteration count" }
        require(keyLength in 1..1024) { "Unsupported key length" }
        val mac = Mac.getInstance("HmacSHA256")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        // An empty key is not accepted by SecretKeySpec, HMAC treats it like a single zero byte
        mac.init(SecretKeySpec(if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes, "HmacSHA256"))
        val salt = base64DecodeArray(saltBase64)
        val output = ByteArrayOutputStream()
        var blockIndex = 1
        while (output.size() < keyLength) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (blockIndex ushr 24).toByte(), (blockIndex ushr 16).toByte(),
                    (blockIndex ushr 8).toByte(), blockIndex.toByte()
                )
            )
            var u = mac.doFinal()
            val block = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in block.indices) block[j] = (block[j].toInt() xor u[j].toInt()).toByte()
            }
            output.write(block)
            blockIndex++
        }
        return base64Encode(output.toByteArray().copyOf(keyLength))
    }

    private fun envelope(value: Any?): String = mapOf("ok" to true, "value" to value).toJsonText()

    private fun failure(message: String): String = mapOf("ok" to false, "error" to message).toJsonText()

    companion object {
        private const val TAG = "AddonHostBridge"
        private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
        private const val MAX_ERROR_LENGTH = 2000
        private const val MAX_SLEEP_MS = 10L * 60L * 1000L
        private const val HTTP_CALL_TIMEOUT_SECONDS = 60L
        private const val EXTRACT_TIMEOUT_MS = 45_000L
        private const val MAX_PBKDF2_ITERATIONS = 2_000_000

        /** Each loaded plugin keeps its own script environment alive, which costs memory. */
        private const val MAX_LOADED_PLUGINS = 10
        private val MANAGED_HEADERS = setOf("accept-encoding", "content-length", "host", "connection")

        /** [text] as a JavaScript string literal. */
        fun quote(text: String): String {
            // JSON is valid JavaScript, except for these two line separators in old engines
            return text.toJsonText().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        }
    }
}
