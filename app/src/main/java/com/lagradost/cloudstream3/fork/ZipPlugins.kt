package com.lagradost.cloudstream3.fork

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.ui.settings.AssetPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.lingala.zip4j.exception.ZipException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plus > Zip plugin. A .zip full of .cs3 files, from the link the admin gives ("plugin_zip_url")
 * or from the phone. When the zip has a password, the user types it (it is not kept anywhere).
 * The plugins inside are confirmed one by one and then installed like the ones of
 * "Dosyadan eklentiler", where they can be named, switched off or deleted.
 * Switched off from outside with "zip_plugin" (in general or for a key).
 */
object ZipPlugins {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX = 200L * 1024 * 1024

    fun available(context: Context?): Boolean =
        FilePlugins.available(context) && ForkFeatures.isOn(Feature.ZIP_PLUGIN, context)

    fun url(context: Context?): String? =
        ForkFeatures.general(context)?.let { RemoteRules.text(it, "plugin_zip_url") }?.trim()?.takeIf { it.startsWith("http") }

    fun name(context: Context?): String? =
        ForkFeatures.general(context)?.let { RemoteRules.text(it, "plugin_zip_name") }?.trim()?.takeIf { it.isNotEmpty() }

    private fun dir(context: Context) = File(context.cacheDir, "fork_zip_plugins").apply { mkdirs() }
    private fun zipOf(context: Context, id: String) = File(dir(context), "$id.zip")

    private fun encrypted(f: File): Boolean? = try {
        val z = net.lingala.zip4j.ZipFile(f)
        if (!z.isValidZipFile) null else z.isEncrypted
    } catch (t: Throwable) {
        null
    }

    /** What the page gets for a zip that is now on the phone. */
    private fun ready(context: Context, f: File, name: String): JSONObject {
        val enc = encrypted(f)
        if (enc == null) {
            f.delete()
            return JSONObject().put("ok", false).put("message", "Bu bir zip dosyası değil ya da bozuk")
        }
        return JSONObject().put("ok", true).put("zip", f.nameWithoutExtension).put("name", name).put("encrypted", enc)
    }

    /** Follows redirects (GitHub sends release files to another host). */
    private fun connect(link: String): HttpURLConnection {
        var u = URL(link)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "CloudStream")
            val loc = if (c.responseCode in 300..399) c.getHeaderField("Location") else null
            if (loc == null) return c
            c.disconnect()
            u = URL(u, loc)
        }
        throw IllegalStateException("redirects")
    }

    private fun newId() = System.currentTimeMillis().toString(36) + (1000..9999).random()

    /**
     * The .cs3 files inside, checked and kept until they are confirmed.
     * A wrong password gives { ok:false, wrong:true }.
     */
    private fun openZip(context: Context, id: String, password: String?): JSONObject {
        val f = zipOf(context, id)
        if (!f.exists()) return JSONObject().put("ok", false).put("message", "Zip bulunamadı, tekrar indir")
        val zip = net.lingala.zip4j.ZipFile(f, password?.takeIf { it.isNotEmpty() }?.toCharArray())
        val enc = try { zip.isEncrypted } catch (t: Throwable) { false }
        if (enc && password.isNullOrEmpty()) return JSONObject().put("ok", false).put("wrong", true).put("message", "Şifreyi gir")
        val found = JSONArray()
        var bad = 0
        try {
            val headers = zip.fileHeaders.filter {
                !it.isDirectory && it.fileName.endsWith(".cs3", true) && !it.fileName.startsWith("__MACOSX")
            }
            if (headers.isEmpty()) return JSONObject().put("ok", false).put("message", "Zip içinde .cs3 dosyası yok")
            for (h in headers) {
                if (h.uncompressedSize > MAX) { bad++; continue }
                // read into memory; the name inside the zip is only used as a label, never as a path
                val bytes = zip.getInputStream(h).use { it.readBytes() }
                val r = FilePlugins.inspectBytes(context, h.fileName.substringAfterLast('/'), bytes)
                if (r.optBoolean("ok")) found.put(r) else bad++
            }
        } catch (e: ZipException) {
            // nothing half done stays behind
            for (i in 0 until found.length()) FilePlugins.cancelPending(context, found.optJSONObject(i)?.optString("pending"))
            return if (enc) JSONObject().put("ok", false).put("wrong", true).put("message", "Şifre yanlış, tekrar dene")
            else JSONObject().put("ok", false).put("message", "Zip açılamadı")
        } catch (t: Throwable) {
            for (i in 0 until found.length()) FilePlugins.cancelPending(context, found.optJSONObject(i)?.optString("pending"))
            return JSONObject().put("ok", false).put("wrong", enc).put("message", if (enc) "Şifre yanlış, tekrar dene" else "Zip açılamadı")
        }
        f.delete()
        if (found.length() == 0) return JSONObject().put("ok", false).put("message", "Zip içinde geçerli eklenti bulunamadı")
        return JSONObject().put("ok", true).put("items", found).put("skipped", bad)
    }

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private fun js(script: String) = handle?.runScript(script)
        private fun answer(token: Int, json: JSONObject) =
            js("window.onZipResult && window.onZipResult($token, ${JSONObject.quote(json.toString())})")

        @JavascriptInterface
        fun config(): String = JSONObject().put("url", url(activity) ?: "").put("name", name(activity) ?: "").toString()

        /** The zip from the admin's link, with window.onZipProgress(percent) on the way. */
        @JavascriptInterface
        fun download(token: Int) {
            scope.launch {
                val link = url(activity)
                if (link == null) {
                    answer(token, JSONObject().put("ok", false).put("message", "Paket bağlantısı yok"))
                    return@launch
                }
                val f = zipOf(activity, newId())
                val out = try {
                    val c = connect(link)
                    if (c.responseCode !in 200..299) {
                        JSONObject().put("ok", false).put("message", "İndirilemedi (HTTP ${c.responseCode})")
                    } else {
                        val total = c.contentLengthLong
                        if (total > MAX) throw IllegalStateException("too big")
                        var got = 0L
                        var last = -1
                        c.inputStream.use { input ->
                            f.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    got += n
                                    if (got > MAX) throw IllegalStateException("too big")
                                    if (total > 0) {
                                        val p = (got * 100 / total).toInt()
                                        if (p != last) {
                                            last = p
                                            js("window.onZipProgress && window.onZipProgress($p)")
                                        }
                                    }
                                }
                            }
                        }
                        c.disconnect()
                        val name = link.substringAfterLast('/').substringBefore('?').ifBlank { "paket.zip" }
                        ready(activity, f, name)
                    }
                } catch (t: Throwable) {
                    f.delete()
                    JSONObject().put("ok", false).put("message",
                        if (t.message == "too big") "Paket çok büyük" else "İndirilemedi, bağlantını kontrol et")
                }
                answer(token, out)
            }
        }

        /** A zip picked from the phone, as base64. */
        @JavascriptInterface
        fun pick(token: Int, fileName: String?, base64: String?) {
            scope.launch {
                val f = zipOf(activity, newId())
                val out = try {
                    f.writeBytes(Base64.decode(base64?.substringAfter("base64,") ?: "", Base64.DEFAULT))
                    ready(activity, f, fileName?.ifBlank { null } ?: "paket.zip")
                } catch (t: Throwable) {
                    f.delete()
                    JSONObject().put("ok", false).put("message", "Dosya okunamadı")
                }
                answer(token, out)
            }
        }

        @JavascriptInterface
        fun open(token: Int, zip: String?, password: String?) {
            val id = zip?.takeIf { it.matches(Regex("[a-z0-9]+")) } ?: return
            scope.launch {
                val out = try {
                    openZip(activity, id, password)
                } catch (t: Throwable) {
                    JSONObject().put("ok", false).put("message", "Zip açılamadı")
                }
                answer(token, out)
            }
        }

        /** The zip is given up (the password sheet was closed). */
        @JavascriptInterface
        fun drop(zip: String?) {
            val id = zip?.takeIf { it.matches(Regex("[a-z0-9]+")) } ?: return
            zipOf(activity, id).delete()
        }

        @JavascriptInterface
        fun install(token: Int, pendingId: String?, label: String?) {
            pendingId ?: return
            scope.launch { answer(token, FilePlugins.installPending(activity, pendingId, label)) }
        }

        @JavascriptInterface
        fun cancel(pendingId: String?) = FilePlugins.cancelPending(activity, pendingId)

        @JavascriptInterface
        fun openFilePlugins() {
            activity.runOnUiThread { FilePlugins.show(activity) }
        }
    }

    fun show(activity: FragmentActivity) {
        // leftovers of an earlier time
        try { dir(activity).listFiles()?.forEach { it.delete() } } catch (t: Throwable) {}
        val bridge = Bridge(activity)
        bridge.handle = AssetPage.show(activity, "zip-plugins.html", bridges = mapOf("ZipPlugins" to bridge))
    }
}
