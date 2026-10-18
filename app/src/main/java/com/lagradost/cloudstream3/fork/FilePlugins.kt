package com.lagradost.cloudstream3.fork

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.AssetPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Plugins (.cs3) picked from the phone's files (Plus > Dosyadan eklentiler). They are copied
 * into the app's own folder, so no storage permission is needed, and loaded like the others.
 * Each one can get a name of its own, be switched off (unloaded) or deleted.
 */
object FilePlugins {
    private const val TAG = "ForkFilePlugins"
    private const val PREFS = "fork_file_plugins"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.FILE_PLUGINS, context)

    class Entry(
        val id: String, val label: String, val fileName: String, val plugin: String, val version: Int,
        val size: Long, val added: Long, val on: Boolean,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("label", label).put("fileName", fileName).put("plugin", plugin)
            .put("version", version).put("size", size).put("added", added).put("on", on)
    }

    private fun dir(context: Context) = File(context.filesDir, "fork_file_plugins").apply { mkdirs() }
    private fun pending(context: Context) = File(context.cacheDir, "fork_file_plugins_new").apply { mkdirs() }
    fun fileOf(context: Context, id: String) = File(dir(context), "$id.cs3")

    fun list(context: Context): List<Entry> = try {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Entry(it.optString("id"), it.optString("label"), it.optString("fileName"), it.optString("plugin"), it.optInt("version"),
                it.optLong("size"), it.optLong("added"), it.optBoolean("on", true))
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun save(context: Context, list: List<Entry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", JSONArray(list.map { it.json() }).toString()).apply()
    }

    private fun replace(context: Context, id: String, change: (Entry) -> Entry) {
        save(context, list(context).map { if (it.id == id) change(it) else it })
    }

    /** name and version from the manifest.json inside the .cs3; null when it is not a plugin */
    private fun manifest(file: File): Pair<String, Int>? = try {
        ZipFile(file).use { zip ->
            val e = zip.getEntry("manifest.json") ?: return null
            val j = JSONObject(zip.getInputStream(e).bufferedReader().readText())
            if (j.optString("pluginClassName").isBlank()) return null
            (j.optString("name").ifBlank { file.nameWithoutExtension }) to j.optInt("version", 0)
        }
    } catch (t: Throwable) {
        null
    }

    fun providersOf(context: Context, id: String): List<String> {
        val path = fileOf(context, id).absolutePath
        return try {
            APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it.sourcePlugin == path }.map { it.name } }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun isLoaded(context: Context, id: String) = PluginManager.plugins.containsKey(fileOf(context, id).absolutePath)

    /** why a plugin could not be loaded, by id */
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun load(context: Context, e: Entry): Boolean {
        val f = fileOf(context, e.id)
        if (!f.exists()) {
            errors[e.id] = "Dosya bulunamadı"
            return false
        }
        if (isLoaded(context, e.id)) return true
        val ok = PluginManager.forkLoadFilePlugin(context, f, e.plugin)
        if (ok) errors.remove(e.id) else errors[e.id] = PluginManager.forkLastLoadError ?: "Yüklenemedi"
        return ok
    }

    /**
     * A source from a file and one from a repository with the same name cannot both be used (the app
     * finds sources by name): the one from the file wins, the other one waits until the file one is
     * switched off or deleted.
     */
    fun <T : com.lagradost.cloudstream3.MainAPI> preferFileCopies(list: List<T>): List<T> {
        val fromFiles = list.filter { SourceGroups.isFilePlugin(it) }.map { it.name }.toSet()
        if (fromFiles.isEmpty()) return list
        return list.filter { SourceGroups.isFilePlugin(it) || it.name !in fromFiles }
    }

    /** Puts the copies from files before the others in the list of sources right away. */
    private fun preferNow() {
        try {
            val now = APIHolder.apis.toList()
            val inList = now.toHashSet()
            val missing = APIHolder.allProviders.withLock { APIHolder.allProviders.filter { SourceGroups.isFilePlugin(it) && it !in inList } }
            APIHolder.apis.withLock { APIHolder.apis = com.lagradost.cloudstream3.utils.AtomicList(preferFileCopies(now + missing)) }
            APIHolder.apiMap = null
        } catch (t: Throwable) {
        }
    }

    /** The other installed sources with the same name as the ones of this plugin */
    private fun replaced(context: Context, id: String): List<String> {
        val path = fileOf(context, id).absolutePath
        return try {
            APIHolder.allProviders.withLock {
                val mine = APIHolder.allProviders.filter { it.sourcePlugin == path }.map { it.name }.toSet()
                APIHolder.allProviders.filter { it.sourcePlugin != path && it.name in mine && !SourceGroups.isFilePlugin(it) }
                    .map { it.name }.distinct()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** The sources of a plugin, and why the home page picker would not show one. */
    fun sourcesOf(context: Context, id: String): JSONArray {
        val path = fileOf(context, id).absolutePath
        val apis = try {
            APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it.sourcePlugin == path } }
        } catch (t: Throwable) {
            emptyList()
        }
        val shown = try {
            with(com.lagradost.cloudstream3.utils.AppContextUtils) { context.filterProviderByPreferredMedia() }.toSet()
        } catch (t: Throwable) {
            null
        }
        val langs = try {
            with(com.lagradost.cloudstream3.utils.AppContextUtils) { context.getApiProviderLangSettings() }
        } catch (t: Throwable) {
            null
        }
        val out = JSONArray()
        apis.forEach { api ->
            val why = when {
                APIHolder.apis.none { it === api } -> "Aynı isimli başka bir kaynak öncelikli; uygulamayı yeniden aç"
                !api.hasMainPage -> "Ana sayfası yok, sadece aramada çıkar"
                langs != null && !langs.contains(com.lagradost.cloudstream3.AllLanguagesName) && !langs.contains(api.lang) ->
                    "Dil ayarı gizliyor (${api.lang})"
                shown != null && api !in shown -> "Tür tercihleri gizliyor"
                else -> null
            }
            out.put(JSONObject().put("name", api.name).put("lang", api.lang).put("why", why ?: JSONObject.NULL))
        }
        return out
    }

    private fun unload(context: Context, id: String) {
        val path = fileOf(context, id).absolutePath
        if (PluginManager.plugins.containsKey(path)) PluginManager.unloadPlugin(path)
    }

    private suspend fun refresh() = withContext(Dispatchers.Main) {
        preferNow()
        try {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        } catch (t: Throwable) {
        }
    }

    /** At start, after the other plugins: the ones that are switched on. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            if (!available(ctx)) return@launch
            lock.withLock {
                var any = false
                list(ctx).filter { it.on }.forEach { if (load(ctx, it)) any = true }
                if (any) refresh()
            }
        }
    }

    // ---------------------------------------------------------------- shared with Plus > Zip plugin

    /** A .cs3 (as bytes) is checked and kept until it is confirmed with [installPending]. */
    fun inspectBytes(context: Context, fileName: String?, bytes: ByteArray): JSONObject {
        val out = JSONObject().put("fileName", fileName ?: "")
        val id = System.currentTimeMillis().toString(36) + (1000..9999).random()
        val f = File(pending(context), "$id.cs3").apply { writeBytes(bytes) }
        val m = manifest(f)
        if (m == null) {
            f.delete()
            return out.put("ok", false).put("message", "Bu bir CloudStream eklentisi (.cs3) değil")
        }
        val same = list(context).firstOrNull { it.plugin.equals(m.first, true) }
        return out.put("ok", true).put("pending", id).put("plugin", m.first).put("version", m.second).put("size", bytes.size)
            .put("replaces", same?.id ?: JSONObject.NULL).put("replacesLabel", same?.label ?: JSONObject.NULL)
            .put("label", (fileName ?: m.first).substringAfterLast('/').substringBeforeLast(".cs3").substringBeforeLast(".CS3").ifBlank { m.first })
    }

    fun cancelPending(context: Context, pendingId: String?) {
        File(pending(context), "${pendingId ?: return}.cs3").delete()
    }

    suspend fun installPending(context: Context, pendingId: String, label: String?): JSONObject {
        val out = JSONObject()
        lock.withLock {
            val src = File(pending(context), "$pendingId.cs3")
            val m = if (src.exists()) manifest(src) else null
            if (m == null) return out.put("ok", false).put("message", "Dosya bulunamadı, tekrar seç")
            // a newer copy of the same plugin takes the place of the old one
            list(context).filter { it.plugin.equals(m.first, true) }.forEach { old ->
                unload(context, old.id)
                fileOf(context, old.id).apply { setWritable(true) }.delete()
                save(context, list(context).filter { it.id != old.id })
            }
            val dest = fileOf(context, pendingId)
            src.copyTo(dest, overwrite = true)
            src.delete()
            val e = Entry(pendingId, label?.trim()?.ifBlank { null } ?: m.first, src.name, m.first, m.second, dest.length(), System.currentTimeMillis(), true)
            save(context, list(context) + e)
            val ok = load(context, e)
            refresh()
            val n = sourcesOf(context, e.id).length()
            out.put("ok", ok).put("label", e.label).put("message", if (!ok) "Yüklenemedi: " + (errors[e.id] ?: "uyumsuz eklenti")
                else if (n == 0) "${e.label} yüklendi ama kaynak eklemedi" else "${e.label} yüklendi · $n kaynak")
        }
        return out
    }

    // ---------------------------------------------------------------- the page

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private fun js(script: String) = handle?.runScript(script)

        private fun answer(token: Int, json: JSONObject) =
            js("window.onFpResult && window.onFpResult($token, ${JSONObject.quote(json.toString())})")

        @JavascriptInterface
        fun list(): String {
            val a = JSONArray()
            FilePlugins.list(activity).forEach { e ->
                a.put(e.json().put("loaded", isLoaded(activity, e.id)).put("sources", sourcesOf(activity, e.id))
                    .put("replaces", JSONArray(if (e.on) replaced(activity, e.id) else emptyList()))
                    .put("error", errors[e.id] ?: JSONObject.NULL))
            }
            return a.toString()
        }

        /** A picked file, as base64: checked and kept until it is confirmed. */
        @JavascriptInterface
        fun inspect(token: Int, fileName: String?, base64: String?) {
            scope.launch {
                val out = try {
                    val bytes = Base64.decode(base64?.substringAfter("base64,") ?: "", Base64.DEFAULT)
                    inspectBytes(activity, fileName, bytes)
                } catch (t: Throwable) {
                    JSONObject().put("fileName", fileName ?: "").put("ok", false).put("message", "Dosya okunamadı")
                }
                answer(token, out)
            }
        }

        @JavascriptInterface
        fun install(token: Int, pendingId: String?, label: String?) {
            pendingId ?: return
            scope.launch { answer(token, installPending(activity, pendingId, label)) }
        }

        @JavascriptInterface
        fun cancel(pendingId: String?) {
            cancelPending(activity, pendingId)
        }

        @JavascriptInterface
        fun rename(id: String?, label: String?) {
            val l = label?.trim()?.ifBlank { null } ?: return
            replace(activity, id ?: return) { Entry(it.id, l, it.fileName, it.plugin, it.version, it.size, it.added, it.on) }
        }

        @JavascriptInterface
        fun setOn(token: Int, id: String?, on: Boolean) {
            id ?: return
            scope.launch {
                lock.withLock {
                    replace(activity, id) { Entry(it.id, it.label, it.fileName, it.plugin, it.version, it.size, it.added, on) }
                    val e = FilePlugins.list(activity).firstOrNull { it.id == id } ?: return@launch
                    val ok = if (on) load(activity, e) else {
                        unload(activity, id); true
                    }
                    refresh()
                    answer(token, JSONObject().put("ok", ok).put("message", if (!ok) "Eklenti yüklenemedi" else if (on) "${e.label} açıldı" else "${e.label} kapatıldı"))
                }
            }
        }

        @JavascriptInterface
        fun remove(token: Int, id: String?) {
            id ?: return
            scope.launch {
                lock.withLock {
                    val e = FilePlugins.list(activity).firstOrNull { it.id == id }
                    unload(activity, id)
                    fileOf(activity, id).apply { setWritable(true) }.delete()
                    save(activity, FilePlugins.list(activity).filter { it.id != id })
                    refresh()
                    answer(token, JSONObject().put("ok", true).put("message", "${e?.label ?: "Eklenti"} silindi"))
                }
            }
        }
    }

    fun show(activity: FragmentActivity) {
        val bridge = Bridge(activity)
        bridge.handle = AssetPage.show(activity, "file-plugins.html", bridges = mapOf("FilePlugins" to bridge))
    }

    @Suppress("unused")
    private fun log(msg: String) = Log.i(TAG, msg)
}
