package com.lagradost.cloudstream3.fork

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Turkish subtitles made from English ones when there are no Turkish ones.
 *
 * The player gets one more subtitle, "Türkçe (otomatik çeviri)", made from the English one.
 * Two ways, chosen in Plus:
 *  - before: the whole subtitle is translated as soon as the links are there, so it is ready
 *    when the film starts and nothing is done while watching.
 *  - live: the subtitle starts in English at once and every line turns Turkish as soon as it is
 *    translated in the background.
 * Switched off from outside with "auto_subtitles". Translations are kept, a second watch is instant.
 */
object AutoSubtitles {
    private const val TAG = "AutoSubtitles"
    const val PREF_ON = "fork_autosub"
    const val PREF_MODE = "fork_autosub_mode"
    const val MODE_BEFORE = "before"
    const val MODE_LIVE = "live"

    /** The name of the made subtitle, the decoder knows it by this */
    const val NAME = "Türkçe (otomatik çeviri)"

    private const val BATCH_LINES = 40
    private const val BATCH_CHARS = 3500
    private const val PARALLEL = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Line of a subtitle (normalised) -> Turkish */
    private val memory = ConcurrentHashMap<String, String>()

    /** The translations that run, by the address of the English subtitle */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Subtitle addresses translated completely */
    private val done = ConcurrentHashMap.newKeySet<String>()

    /** Subtitle addresses whose kept translations are already read */
    private val loadedFiles = ConcurrentHashMap.newKeySet<String>()

    /** The text track on screen is the made one and lines are swapped while it is shown */
    @Volatile
    var liveActive = false

    fun enabled(context: Context? = CloudStreamApp.context): Boolean {
        context ?: return false
        if (!ForkFeatures.isOn(Feature.AUTO_SUBS, context)) return false
        return try {
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ON, true)
        } catch (t: Throwable) {
            false
        }
    }

    fun mode(context: Context? = CloudStreamApp.context): String = try {
        context?.let { PreferenceManager.getDefaultSharedPreferences(it).getString(PREF_MODE, MODE_BEFORE) }
            ?: MODE_BEFORE
    } catch (t: Throwable) {
        MODE_BEFORE
    }

    fun isMade(sub: SubtitleData): Boolean = sub.originalName == NAME

    /** Is the subtitle the decoder got the made one: its name is in the language field. */
    fun isMadeTrack(formatLanguage: String?): Boolean = formatLanguage?.contains(NAME) == true

    /** The English subtitle address from the id of the made track ("url|name"). */
    fun urlFromTrackId(id: String?): String? = id?.substringBeforeLast("|")?.ifBlank { null }

    private fun isTurkish(sub: SubtitleData): Boolean {
        val tag = sub.getIETF_tag()?.lowercase() ?: return false
        return tag == "tr" || tag.startsWith("tr-")
    }

    private fun isEnglish(sub: SubtitleData): Boolean {
        val tag = sub.getIETF_tag()?.lowercase() ?: return false
        return tag == "en" || tag.startsWith("en-")
    }

    /**
     * The subtitles of a video with the made Turkish one added, when there is an English one and
     * no Turkish one. In the "before" way the translation starts here.
     */
    fun withMade(subtitles: Collection<SubtitleData>): Collection<SubtitleData> {
        val real = subtitles.filterNot { isMade(it) }
        if (!enabled()) return real
        if (real.any { isTurkish(it) }) return real
        val english = real.firstOrNull { isEnglish(it) && it.origin != SubtitleOrigin.EMBEDDED_IN_VIDEO }
            ?: return real
        val made = SubtitleData(
            originalName = NAME,
            nameSuffix = "",
            url = english.url,
            origin = english.origin,
            mimeType = english.mimeType,
            headers = english.headers,
            languageCode = "tr",
        )
        if (mode() == MODE_BEFORE) prepare(english)
        return real + made
    }

    // ---------------------------------------------------------------- the lines

    private val tagRegex = Regex("""<[^>]*>|\{[^}]*\}""")
    private val spaceRegex = Regex("""\s+""")
    private val timeRegex = Regex("""^\s*\d{1,2}:\d{2}(:\d{2})?[.,]\d{1,3}\s*-->""")

    /** The same line written the same way, wherever it comes from. */
    fun key(text: String): String = text.replace(tagRegex, " ").replace(spaceRegex, " ").trim()

    /** The lines of an SRT or VTT file, as the player will show them. */
    private fun linesOf(file: String): List<String> {
        val out = ArrayList<String>()
        val block = StringBuilder()
        var inCue = false
        fun flush() {
            if (block.isNotBlank()) out.add(key(block.toString()))
            block.clear()
            inCue = false
        }
        for (raw in file.replace("\r", "").split('\n')) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                timeRegex.containsMatchIn(line) -> {
                    flush()
                    inCue = true
                }
                inCue -> block.append(line).append(' ')
            }
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    /** The Turkish of a line on screen, or the line itself. */
    fun forScreen(text: String): String {
        if (!liveActive) return text
        return memory[key(text)] ?: text
    }

    fun cached(text: String): String? = memory[key(text)]

    // ---------------------------------------------------------------- keeping

    private fun fileFor(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "auto_subtitles").apply { mkdirs() }, "$hash.json")
    }

    private fun loadKept(url: String) {
        val context = CloudStreamApp.context ?: return
        if (!loadedFiles.add(url)) return
        try {
            val file = fileFor(context, url)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.keys().forEach { k -> memory[k] = json.getString(k) }
        } catch (t: Throwable) {
            Log.w(TAG, "kept translation unreadable", t)
        }
    }

    private fun keep(url: String, lines: Collection<String>) {
        val context = CloudStreamApp.context ?: return
        try {
            val json = JSONObject()
            lines.forEach { line -> memory[line]?.let { json.put(line, it) } }
            fileFor(context, url).writeText(json.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "could not keep the translation", t)
        }
    }

    // ---------------------------------------------------------------- translating

    /** Starts translating the English subtitle in the background, once per address. */
    fun prepare(english: SubtitleData) {
        val url = english.getFixedUrl()
        if (jobs[url]?.isActive == true || done.contains(url)) return
        jobs[url] = scope.launch {
            try {
                loadKept(url)
                val text = if (english.origin == SubtitleOrigin.DOWNLOADED_FILE) {
                    val ctx = CloudStreamApp.context ?: return@launch
                    if (url.startsWith("/")) File(url).readText()
                    else ctx.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes().decodeToString() }
                        ?: return@launch
                } else {
                    app.get(url, headers = english.headers).text
                }
                val lines = linesOf(text).distinct()
                translate(lines)
                keep(url, lines)
                if (lines.all { memory.containsKey(it) }) done.add(url)
                Log.i(TAG, "ready ${lines.size} lines")
            } catch (t: Throwable) {
                Log.w(TAG, "prepare failed", t)
            }
        }
    }

    /**
     * Called by the decoder with the lines of the made track. In the "before" way it waits until
     * everything is translated (the film plays meanwhile), in the "live" way it returns at once and
     * the lines are swapped on screen when ready.
     */
    fun forDecoder(url: String?, lines: List<String>, onWaiting: () -> Unit): Boolean {
        val keys = lines.map { key(it) }.filter { it.isNotBlank() }.distinct()
        if (url != null) loadKept(url)
        val live = mode() == MODE_LIVE
        liveActive = live
        if (live) {
            if (url == null || jobs[url]?.isActive != true) {
                val job = scope.launch {
                    translate(keys)
                    if (url != null) keep(url, keys)
                }
                if (url != null) jobs[url] = job
            }
            return false
        }
        if (keys.all { memory.containsKey(it) }) return true
        onWaiting()
        runBlocking {
            // A translation that already runs for this subtitle is finished first
            url?.let { jobs[it] }?.let { withTimeoutOrNull(120_000) { it.join() } }
            translate(keys)
        }
        if (url != null) keep(url, keys)
        return true
    }

    private suspend fun translate(lines: List<String>) {
        val missing = lines.filter { it.isNotBlank() && !memory.containsKey(it) }
        if (missing.isEmpty()) return
        val batches = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var size = 0
        for (line in missing) {
            if (current.size >= BATCH_LINES || size + line.length > BATCH_CHARS) {
                batches.add(current)
                current = ArrayList()
                size = 0
            }
            current.add(line)
            size += line.length + 1
        }
        if (current.isNotEmpty()) batches.add(current)
        val gate = Semaphore(PARALLEL)
        coroutineScopeAll(batches.map { batch -> suspend { gate.withPermit { translateBatch(batch) } } })
    }

    private suspend fun coroutineScopeAll(work: List<suspend () -> Unit>) {
        kotlinx.coroutines.coroutineScope {
            work.map { w -> async { try { w() } catch (t: Throwable) { Log.w(TAG, "batch failed", t) } } }.awaitAll()
        }
    }

    private suspend fun translateBatch(batch: List<String>) {
        val joined = batch.joinToString("\n")
        val result = google(joined)?.split('\n')?.map { it.trim() }
        if (result != null && result.size == batch.size) {
            batch.forEachIndexed { i, line -> if (result[i].isNotBlank()) memory[line] = result[i] }
            return
        }
        // The lines did not come back one for one: each on its own
        for (line in batch) {
            google(line)?.trim()?.takeIf { it.isNotBlank() }?.let { memory[line] = it.replace('\n', ' ') }
        }
    }

    /** Google's free translation, English to Turkish. */
    private suspend fun google(text: String): String? {
        return try {
            val response = app.post(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t",
                data = mapOf("q" to text),
            )
            if (response.code !in 200..299) return null
            val parts = JSONArray(response.text).optJSONArray(0) ?: return null
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val piece = parts.optJSONArray(i)?.optString(0) ?: continue
                if (piece != "null") out.append(piece)
            }
            out.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "translate failed", t)
            null
        }
    }
}
