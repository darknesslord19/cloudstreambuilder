package com.lagradost.cloudstream3.addons.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.addons.AddonException
import com.lagradost.cloudstream3.addons.AddonTimeoutException
import com.lagradost.cloudstream3.addons.js.AddonHostBridge
import com.lagradost.cloudstream3.addons.js.AddonJsEngine
import com.lagradost.cloudstream3.addons.js.JsPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs plugin JavaScript in a WebView that is never shown. The WebView is the system's own
 * browser engine, so plugins get a complete and fast modern JavaScript environment, and the
 * app does not need to ship an engine of its own.
 *
 * The page loads assets/addons/bootstrap.js, which gives every plugin a private iframe.
 * Scripts reach the app through [HostInterface] (`window.__csHost` in the page), the app
 * reaches the scripts with evaluateJavascript. All real work is done by [bridge], this class
 * only owns the WebView.
 */
class WebViewJsEngine(
    private val appContext: Context,
    private val bridge: AddonHostBridge,
    private val readAsset: (String) -> String,
) : AddonJsEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startMutex = Mutex()

    /** Only touched on the main thread, as WebView demands. */
    private var webView: WebView? = null

    @Volatile
    private var isReady = false

    init {
        bridge.signal = { requestId ->
            evaluate("__cs.onNative(" + AddonHostBridge.quote(requestId) + ");")
        }
    }

    override suspend fun call(
        plugin: JsPlugin,
        function: String,
        args: List<Any?>,
        timeoutMs: Long,
    ): JsonNode? {
        ensureStarted()
        try {
            return bridge.call(plugin, function, args, timeoutMs) { script -> evaluate(script) }
        } catch (e: AddonTimeoutException) {
            // A script stuck in an endless loop blocks every plugin, find out if that happened
            restartIfUnresponsive()
            throw e
        }
    }

    override fun unload(pluginKey: String) {
        bridge.forget(pluginKey)
        if (isReady) evaluate("__cs.unload(" + AddonHostBridge.quote(pluginKey) + ");")
    }

    override fun unloadAll() {
        if (isReady) evaluate("__cs.unloadAll();")
    }

    private suspend fun ensureStarted() {
        if (isReady) return
        startMutex.withLock {
            if (isReady) return
            // Plugins do their requests through the app, so the page is first created with its
            // own network access switched off. Should a WebView version not start that way,
            // the page is created once more with the default settings.
            if (!start(blockNetwork = true, FIRST_START_TIMEOUT_MS) &&
                !start(blockNetwork = false, START_TIMEOUT_MS)
            ) {
                throw AddonException("The script engine did not start")
            }
            isReady = true
        }
    }

    /**
     * @return false if the page was not ready in time.
     * @throws AddonException if there is no usable WebView at all.
     */
    private suspend fun start(blockNetwork: Boolean, timeoutMs: Long): Boolean {
        val starting = CompletableDeferred<Unit>()
        mainHandler.post { createWebView(starting, blockNetwork) }
        return try {
            withTimeout(timeoutMs) { starting.await() }
            true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "The page did not start in time (network blocked: $blockNetwork)")
            mainHandler.post { destroyWebView() }
            false
        }
    }

    private fun evaluate(script: String) {
        mainHandler.post {
            try {
                webView?.evaluateJavascript(script, null)
            } catch (t: Throwable) {
                Log.e(TAG, "evaluateJavascript failed: ${t.message}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(starting: CompletableDeferred<Unit>, blockNetwork: Boolean) {
        try {
            destroyWebView()
            val bootstrap = readAsset("bootstrap.js")
            val view = WebView(appContext)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = true
                blockNetworkLoads = blockNetwork
            }
            view.addJavascriptInterface(HostInterface(), "__csHost")

            var bootstrapped = false
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (bootstrapped || view == null) return
                    bootstrapped = true
                    // The trailing expression is what comes back, null means the script failed
                    view.evaluateJavascript("$bootstrap\n;'ready';") { result ->
                        if (result != null && result.contains("ready")) {
                            starting.complete(Unit)
                        } else {
                            starting.completeExceptionally(AddonException("The script engine could not be prepared"))
                        }
                    }
                }

                @RequiresApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // The system took the browser process away (low memory) or it crashed.
                    // Returning true keeps the app alive, a new page is made on the next call.
                    pageLost("The script engine was stopped by the system")
                    return true
                }
            }
            webView = view
            view.loadDataWithBaseURL(PAGE_URL, PAGE_HTML, "text/html", "UTF-8", null)
        } catch (t: Throwable) {
            // Typically: this device has no usable WebView
            Log.e(TAG, "WebView could not be created: ${t.message}")
            starting.completeExceptionally(AddonException("WebView is not available on this device"))
        }
    }

    /** Main thread only. */
    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        try {
            view.stopLoading()
            view.removeJavascriptInterface("__csHost")
            view.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "WebView could not be destroyed: ${t.message}")
        }
    }

    /** Main thread only. Drops the page, every running call fails with [reason]. */
    private fun pageLost(reason: String) {
        isReady = false
        destroyWebView()
        bridge.reset(reason)
    }

    private fun restartIfUnresponsive() {
        val answered = AtomicBoolean(false)
        mainHandler.post {
            try {
                webView?.evaluateJavascript("1") { answered.set(true) }
            } catch (t: Throwable) {
                Log.e(TAG, "Responsiveness check failed: ${t.message}")
            }
        }
        mainHandler.postDelayed({
            if (!answered.get() && isReady) {
                Log.w(TAG, "The page does not answer, restarting it")
                pageLost("The script engine stopped responding and was restarted")
            }
        }, UNRESPONSIVE_AFTER_MS)
    }

    /**
     * What plugin scripts can call. The WebView invokes these on its own background thread,
     * and every argument arrives as text, which keeps the conversions between the two worlds
     * trivial. An exception thrown here surfaces as an exception in the script.
     */
    private inner class HostInterface {
        @JavascriptInterface
        fun getAsset(name: String): String = bridge.getAsset(name)

        @JavascriptInterface
        fun getSource(key: String): String = bridge.getSource(key)

        @JavascriptInterface
        fun getEnv(key: String): String = bridge.getEnv(key)

        @JavascriptInterface
        fun done(callId: String, ok: String, payload: String) = bridge.done(callId, ok, payload)

        @JavascriptInterface
        fun http(requestId: String, requestJson: String) = bridge.http(requestId, requestJson)

        @JavascriptInterface
        fun sleep(requestId: String, millis: String) = bridge.sleep(requestId, millis)

        @JavascriptInterface
        fun extract(requestId: String, url: String, referer: String) = bridge.extract(requestId, url, referer)

        @JavascriptInterface
        fun take(requestId: String): String = bridge.take(requestId)

        @JavascriptInterface
        fun cancel(requestId: String) = bridge.cancel(requestId)

        @JavascriptInterface
        fun log(level: String, tag: String, text: String) = bridge.log(level, tag, text)

        @JavascriptInterface
        fun unpack(code: String): String = bridge.unpack(code)

        @JavascriptInterface
        fun crypto(operation: String, a: String, b: String, c: String, d: String): String =
            bridge.crypto(operation, a, b, c, d)

        @JavascriptInterface
        fun prefGet(namespace: String, key: String): String = bridge.prefGet(namespace, key)

        @JavascriptInterface
        fun prefSet(namespace: String, key: String, hasValue: String, value: String) =
            bridge.prefSet(namespace, key, hasValue, value)
    }

    companion object {
        private const val TAG = "AddonJsEngine"
        private const val FIRST_START_TIMEOUT_MS = 12_000L
        private const val START_TIMEOUT_MS = 25_000L
        private const val UNRESPONSIVE_AFTER_MS = 6_000L

        /**
         * The address the empty host page pretends to have. An https address makes the page
         * a "secure context", which scripts need for the browser's crypto functions.
         */
        private const val PAGE_URL = "https://localhost/"
        private const val PAGE_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
    }
}
