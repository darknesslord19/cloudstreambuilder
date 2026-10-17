package com.lagradost.cloudstream3.fork

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.ui.settings.AssetPage
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * IPTV: M3U / M3U8 lists the user adds in Plus. Every list becomes a source on the home page,
 * its groups ("group-title") are the rows and its channels play in the app's player.
 * The lists are kept in the app and read again from their address every 6 hours.
 * Switched off from outside with "iptv".
 */
object Iptv {
    private const val TAG = "Iptv"
    private const val PREFS = "fork_iptv"
    private const val KEY_LISTS = "lists"
    private const val TAG_PREFIX = "iptv:"
    private const val REFRESH_MS = 6L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Playlist(val id: String, val name: String, val src: String, val on: Boolean, val updated: Long)

    class Channel(
        val name: String,
        val logo: String?,
        val group: String,
        val url: String,
        val userAgent: String?,
        val referer: String?,
    )

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.IPTV, context)

    // ---------------------------------------------------------------- keeping

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lists(context: Context): List<Playlist> = try {
        val array = JSONArray(prefs(context).getString(KEY_LISTS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                Playlist(it.optString("id"), it.optString("name"), it.optString("src"), it.optBoolean("on", true), it.optLong("updated"))
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun saveLists(context: Context, lists: List<Playlist>) {
        val array = JSONArray()
        lists.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("src", it.src).put("on", it.on).put("updated", it.updated))
        }
        prefs(context).edit().putString(KEY_LISTS, array.toString()).apply()
    }

    private fun fileOf(context: Context, id: String) = File(File(context.filesDir, "iptv").apply { mkdirs() }, "$id.m3u")

    private val parsed = ConcurrentHashMap<String, List<Channel>>()

    fun channels(context: Context, id: String): List<Channel> = parsed.getOrPut(id) {
        try {
            parse(fileOf(context, id).readText())
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- reading a list

    private fun attr(line: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private val streamUrl = Regex("^(https?|rtmp|rtsp|udp)://", RegexOption.IGNORE_CASE)

    fun isHlsStream(text: String): Boolean =
        Regex("#EXT-X-(STREAM-INF|TARGETDURATION|MEDIA-SEQUENCE)").containsMatchIn(text)

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var groupLine: String? = null
        var agent: String? = null
        var referer: String? = null
        for (raw in text.removePrefix("﻿").lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> {
                    val lastQuote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', if (lastQuote > 0) lastQuote else 0)
                    name = if (comma >= 0) line.substring(comma + 1).trim() else null
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title")
                }
                line.startsWith("#EXTGRP:", true) -> groupLine = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> agent = line.substringAfter('=').trim()
                line.startsWith("#EXTVLCOPT:http-referrer=", true) -> referer = line.substringAfter('=').trim()
                line.startsWith("#") -> {}
                streamUrl.containsMatchIn(line) -> {
                    val fallback = line.substringBefore('?').substringAfterLast('/').ifEmpty { "Kanal" }
                    out.add(Channel(name?.ifEmpty { null } ?: fallback, logo, group ?: groupLine ?: "Diğer", line, agent, referer))
                    name = null; logo = null; group = null; agent = null; referer = null
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- adding

    class Result(val ok: Boolean, val message: String)

    private fun newId() = "l" + System.currentTimeMillis().toString(36) + (100..999).random()

    private fun store(context: Context, playlist: Playlist, text: String) {
        fileOf(context, playlist.id).writeText(text)
        parsed.remove(playlist.id)
        saveLists(context, lists(context).filter { it.id != playlist.id } + playlist)
        register(context)
    }

    /** A list from an address, also a short link or one stream (.m3u8). */
    suspend fun addUrl(context: Context, name: String?, url: String): Result {
        return try {
            val response = app.get(url, allowRedirects = true)
            val body = response.text
            val text = when {
                response.code !in 200..299 -> null
                isHlsStream(body) || parse(body).isEmpty() && url.contains(".m3u8", true) ->
                    "#EXTM3U\n#EXTINF:-1 group-title=\"Kanallar\",${name ?: "Kanal"}\n$url\n"
                else -> body
            } ?: return Result(false, "Yüklenemedi: HTTP ${response.code}")
            val count = parse(text).size
            if (count == 0) return Result(false, "Listede oynatılabilir kanal bulunamadı")
            val title = name?.trim()?.ifEmpty { null } ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "IPTV" }
            store(context, Playlist(newId(), title, url, true, System.currentTimeMillis()), text)
            Result(true, "\"$title\" eklendi · $count kanal")
        } catch (t: Throwable) {
            Log.w(TAG, "add failed", t)
            if (url.contains(".m3u8", true)) {
                val title = name?.trim()?.ifEmpty { null } ?: "Kanal"
                store(context, Playlist(newId(), title, url, true, System.currentTimeMillis()),
                    "#EXTM3U\n#EXTINF:-1 group-title=\"Kanallar\",$title\n$url\n")
                Result(true, "\"$title\" tek kanal olarak eklendi")
            } else Result(false, "Yüklenemedi: ${t.message ?: "bağlantı hatası"}")
        }
    }

    /** A list from its text (a file or pasted). */
    fun addText(context: Context, name: String?, text: String, src: String): Result {
        val count = parse(text).size
        if (count == 0) return Result(false, "Listede oynatılabilir kanal bulunamadı")
        val title = name?.trim()?.ifEmpty { null } ?: "Liste ${lists(context).size + 1}"
        store(context, Playlist(newId(), title, src, true, System.currentTimeMillis()), text)
        return Result(true, "\"$title\" eklendi · $count kanal")
    }

    fun setOn(context: Context, id: String, on: Boolean) {
        saveLists(context, lists(context).map { if (it.id == id) Playlist(it.id, it.name, it.src, on, it.updated) else it })
        register(context)
    }

    fun remove(context: Context, id: String) {
        saveLists(context, lists(context).filter { it.id != id })
        fileOf(context, id).delete()
        parsed.remove(id)
        register(context)
    }

    // ---------------------------------------------------------------- the sources on the home page

    class Provider(private val list: Playlist) : MainAPI() {
        override var name = "IPTV · ${list.name}"
        override var mainUrl = "iptv://${list.id}"
        override var lang = "tr"
        override val hasMainPage = true
        override val hasQuickSearch = false
        override val supportedTypes = setOf(TvType.Live)

        private fun all(): List<Channel> = CloudStreamApp.context?.let { channels(it, list.id) } ?: emptyList()

        override val mainPage: List<MainPageData> by lazy {
            all().map { it.group }.distinct().take(80).map { MainPageData(it, it) }
        }

        private fun card(index: Int, channel: Channel): SearchResponse =
            newLiveSearchResponse(channel.name, "$mainUrl/$index", TvType.Live, fix = false) {
                posterUrl = channel.logo
            }

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val cards = all().withIndex().filter { it.value.group == request.data }.map { card(it.index, it.value) }
            return newHomePageResponse(com.lagradost.cloudstream3.HomePageList(request.name, cards, isHorizontalImages = true), false)
        }

        override suspend fun search(query: String): List<SearchResponse> {
            val q = query.lowercase()
            return all().withIndex().filter { it.value.name.lowercase().contains(q) }.take(100).map { card(it.index, it.value) }
        }

        private fun channelOf(url: String): Channel? = url.substringAfterLast('/').toIntOrNull()?.let { all().getOrNull(it) }

        override suspend fun load(url: String): LoadResponse? {
            val channel = channelOf(url) ?: return null
            return newLiveStreamLoadResponse(channel.name, url, url) {
                posterUrl = channel.logo
                plot = "${channel.group} · ${list.name}"
            }
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            val channel = channelOf(data) ?: return false
            val path = channel.url.substringBefore('?').lowercase()
            val type = when {
                path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") -> ExtractorLinkType.VIDEO
                else -> ExtractorLinkType.M3U8
            }
            callback(newExtractorLink(name, channel.name, channel.url, type) {
                channel.referer?.let { referer = it }
                channel.userAgent?.let { headers = mapOf("User-Agent" to it) }
            })
            return true
        }
    }

    @Synchronized
    private fun register(context: Context) {
        // the old sources go, the switched on lists come back
        APIHolder.apis.filter { it.sourcePlugin?.startsWith(TAG_PREFIX) == true }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { it.sourcePlugin?.startsWith(TAG_PREFIX) == true }
        }
        if (available(context)) {
            lists(context).filter { it.on }.forEach { list ->
                val provider = Provider(list)
                provider.sourcePlugin = TAG_PREFIX + list.id
                APIHolder.allProviders.add(provider)
                APIHolder.addPluginMapping(provider)
            }
        }
        try {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        } catch (t: Throwable) {
            Log.w(TAG, "home not told", t)
        }
    }

    /** At the start: the lists become sources, old ones are read again from their address. */
    fun init(context: Context) {
        scope.launch {
            register(context)
            val now = System.currentTimeMillis()
            var changed = false
            for (list in lists(context)) {
                if (!list.src.startsWith("http") || now - list.updated < REFRESH_MS) continue
                try {
                    val response = app.get(list.src, allowRedirects = true)
                    val text = response.text
                    if (response.code in 200..299 && !isHlsStream(text) && parse(text).isNotEmpty()) {
                        fileOf(context, list.id).writeText(text)
                        parsed.remove(list.id)
                        changed = true
                    }
                    saveLists(context, lists(context).map { if (it.id == list.id) Playlist(it.id, it.name, it.src, it.on, now) else it })
                } catch (t: Throwable) {
                    Log.w(TAG, "refresh failed", t)
                }
            }
            if (changed) register(context)
        }
    }

    // ---------------------------------------------------------------- the page in Plus

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private fun answer(token: Int, result: Result) {
            val json = JSONObject().put("ok", result.ok).put("message", result.message).toString()
            handle?.runScript("window.onIptvResult && window.onIptvResult($token, ${JSONObject.quote(json)})")
        }

        @JavascriptInterface
        fun lists(): String {
            val array = JSONArray()
            Iptv.lists(activity).forEach { list ->
                val ch = channels(activity, list.id)
                array.put(
                    JSONObject().put("id", list.id).put("name", list.name).put("src", list.src).put("on", list.on)
                        .put("count", ch.size).put("groups", ch.map { it.group }.distinct().size)
                )
            }
            return array.toString()
        }

        @JavascriptInterface
        fun add(token: Int, name: String?, urls: String?) {
            val all = (urls ?: "").split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith("http", true) }
            scope.launch {
                if (all.isEmpty()) return@launch answer(token, Result(false, "Geçerli bir link yaz (http:// ya da https:// ile başlamalı)"))
                val messages = ArrayList<String>()
                var ok = false
                all.forEachIndexed { i, url ->
                    val title = name?.trim()?.ifEmpty { null }?.let { if (all.size > 1) "$it ${i + 1}" else it }
                    val r = addUrl(activity, title, url)
                    ok = ok || r.ok
                    messages.add(r.message)
                }
                answer(token, Result(ok, messages.joinToString("\n")))
            }
        }

        @JavascriptInterface
        fun addText(token: Int, name: String?, text: String?, src: String?) {
            scope.launch { answer(token, Iptv.addText(activity, name, text ?: "", src ?: "metin")) }
        }

        @JavascriptInterface
        fun setOn(id: String?, on: Boolean) {
            id ?: return
            scope.launch { Iptv.setOn(activity, id, on) }
        }

        @JavascriptInterface
        fun remove(id: String?) {
            id ?: return
            scope.launch { Iptv.remove(activity, id) }
        }
    }

    fun show(activity: FragmentActivity) {
        val bridge = Bridge(activity)
        bridge.handle = AssetPage.show(activity, "iptv.html", bridges = mapOf("Iptv" to bridge))
    }
}
