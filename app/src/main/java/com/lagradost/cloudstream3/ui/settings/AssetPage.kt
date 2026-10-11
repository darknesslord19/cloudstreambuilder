package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import java.io.ByteArrayInputStream
import java.io.IOException
import org.json.JSONArray

/** The "Developer" page of the settings, the bundled web page assets/about.html. */
object AboutPage {
    fun show(activity: Activity) = AssetPage.show(activity, "about.html")
}

/**
 * Shows a web page that is bundled in the assets folder full screen, like assets/about.html.
 *
 * The page is a normal web page and can use everything a browser offers (scripts, animations,
 * canvas, storage, media). Files it refers to are read from the assets folder as well, so
 * images, style sheets and scripts can be put next to it. Links that lead somewhere else are
 * opened outside of the app.
 */
object AssetPage {
    /**
     * The assets are served under a normal https address instead of file://, because browsers
     * switch many features off for pages opened straight from a file.
     */
    private const val HOST = "appassets.androidplatform.net"

    /**
     * What a page can ask the app, as `CloudStream.version()` and so on.
     * Every frame of the page can call these, so nothing here may be dangerous to call.
     */
    class PageBridge(
        private val activity: Activity,
        private val onClose: () -> Unit,
        private val onAddRepository: ((url: String, name: String?) -> Unit)?,
        private val addedUrls: () -> List<String>,
    ) {
        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun build(): Int = BuildConfig.FORK_BUILD

        @JavascriptInterface
        fun close() {
            activity.runOnUiThread { onClose() }
        }

        /** If the page was opened from the "Add repository" dialog and can hand an address to it. */
        @JavascriptInterface
        fun canAddRepository(): Boolean = onAddRepository != null

        @JavascriptInterface
        fun addRepository(url: String?) = addRepository(url, null)

        /** Closes the page and adds the repository or add-on at [url], as if the user had typed it. */
        @JavascriptInterface
        fun addRepository(url: String?, name: String?) {
            val address = url?.trim() ?: return
            if (!address.startsWith("https://") && !address.startsWith("http://") &&
                !address.startsWith("stremio://") && !address.startsWith("skystream://")
            ) return
            val add = onAddRepository ?: return
            activity.runOnUiThread {
                onClose()
                add(address, name?.trim()?.takeIf { it.isNotEmpty() })
            }
        }

        /** The addresses that are added already, as a JSON list. */
        @JavascriptInterface
        fun addedRepositories(): String {
            return try {
                JSONArray(addedUrls()).toString()
            } catch (t: Throwable) {
                logError(t)
                "[]"
            }
        }

        @JavascriptInterface
        fun copy(text: String?) {
            if (text.isNullOrEmpty()) return
            activity.runOnUiThread {
                try {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("CloudStream", text))
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        }
    }

    /**
     * @param page name of the file in the assets folder, for example "about.html".
     * @param onAddRepository given when the page may add repositories, called on the main
     * thread after the page has closed.
     * @param addedUrls the addresses the user has added already, the page can mark them.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        activity: Activity,
        page: String,
        onAddRepository: ((url: String, name: String?) -> Unit)? = null,
        addedUrls: () -> List<String> = { emptyList() },
    ) {
        val webView = try {
            WebView(activity)
        } catch (t: Throwable) {
            // The device has no usable WebView
            logError(t)
            showToast(R.string.error, Toast.LENGTH_SHORT)
            return
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            // Keeps the page out from under the status and navigation bars
            fitsSystemWindows = true
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            // The page is served through shouldInterceptRequest, it needs no access to files
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.addJavascriptInterface(
            PageBridge(activity, { dialog.dismiss() }, onAddRepository, addedUrls),
            "CloudStream"
        )

        webView.webChromeClient = object : WebChromeClient() {
            // window.close() in the page closes the page
            override fun onCloseWindow(window: WebView?) {
                dialog.dismiss()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != HOST) return null
                val path = (url.path ?: "").trimStart('/').ifEmpty { page }
                return try {
                    val type = mimeTypeOf(path)
                    val isText = type.startsWith("text/") || type.endsWith("javascript") ||
                            type.endsWith("json") || type.endsWith("xml")
                    WebResourceResponse(type, if (isText) "utf-8" else null, activity.assets.open(path))
                } catch (_: IOException) {
                    WebResourceResponse(
                        "text/plain", "utf-8", 404, "Not Found", emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request == null) return false
                val url = request.url ?: return false
                // Frames inside the page (an embedded video, for example) load where they are
                if (!request.isForMainFrame) return false
                if (url.host == HOST) return false
                openOutside(activity, url)
                return true
            }
        }

        dialog.setContentView(container)
        dialog.setOnKeyListener { _, keyCode, event ->
            // Back first goes back inside the page, then closes it
            if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
                if (event.action == KeyEvent.ACTION_UP) webView.goBack()
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            try {
                webView.stopLoading()
                container.removeView(webView)
                webView.destroy()
            } catch (t: Throwable) {
                logError(t)
            }
        }

        try {
            dialog.show()
            webView.loadUrl("https://$HOST/$page")
        } catch (t: Throwable) {
            logError(t)
        }
    }

    private fun mimeTypeOf(path: String): String {
        return when (val extension = path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "webp" -> "image/webp"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }

    /** Opens a link of the page in the browser, or in the app it belongs to (Telegram, mail). */
    private fun openOutside(activity: Activity, url: Uri) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            // No app on the device can open this kind of link
            logError(t)
            showToast(R.string.error, Toast.LENGTH_SHORT)
        }
    }
}
