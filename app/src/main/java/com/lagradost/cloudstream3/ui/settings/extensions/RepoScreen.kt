package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.addons.AddonKind
import com.lagradost.cloudstream3.addons.AddonManager
import com.lagradost.cloudstream3.fork.ForkFeatures
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.AssetPage
import com.lagradost.cloudstream3.utils.AppContextUtils.addRepositoryDialog
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Extensions > the repositories as one page (repos.html): CloudStream repositories and the
 * Stremio, Nuvio and SkyStream sources in one list, with chips for their kinds, an order of
 * their own, delete with undo and an "Add repository" sheet. A kind switched off by the admin
 * leaves the list (its sources wait under "Kapalı türler") and cannot be added.
 * Switched off in Plus > Görünüm, or from outside with "repo_screen": the old screen is back.
 */
object RepoScreen {
    const val PREF = "fork_repo_screen"
    private const val ORDER = "fork_repo_order"

    fun allowed(context: Context?): Boolean = ForkFeatures.isOn(Feature.REPO_SCREEN, context)

    fun available(context: Context?): Boolean {
        val c = context ?: return false
        return allowed(c) && PreferenceManager.getDefaultSharedPreferences(c).getBoolean(PREF, true)
    }

    private fun typeOf(kind: AddonKind?): String = when (kind) {
        AddonKind.STREMIO -> "st"
        AddonKind.NUVIO -> "nv"
        AddonKind.SKYSTREAM -> "sk"
        else -> "cs"
    }

    private fun kindOn(type: String): Boolean = when (type) {
        "cs" -> true
        else -> AddonKind.values().firstOrNull { typeOf(it) == type }?.let { AddonManager.isKindEnabled(it) } ?: false
    }

    /** Plugin counts of CloudStream repositories, read once per run (they need the network). */
    private val counts = ConcurrentHashMap<String, Int>()

    /**
     * The page of an Extensions screen. [onStats] gives the numbers of the bar under the list,
     * [openRepo] / [openLocal] / [search] go to the old screens while the page waits hidden.
     */
    class Page(
        private val fragment: ExtensionsFragment,
        private val stats: () -> JSONObject?,
        private val onChanged: () -> Unit,
        private val search: () -> Unit,
    ) {
        private val activity = fragment.requireActivity()
        private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        private val ui = Handler(Looper.getMainLooper())
        var handle: AssetPage.Handle? = null
        private var leaving = false

        /** Deletions wait a moment so they can be taken back. */
        private val pending = ConcurrentHashMap<String, Runnable>()

        fun show() {
            handle = AssetPage.show(
                activity, "repos.html",
                bridges = mapOf("Repos" to this),
                onDismiss = {
                    flush()
                    // Back on the page leaves Extensions, not only the page
                    if (!leaving) try {
                        fragment.findNavController().popBackStack()
                    } catch (t: Throwable) {
                        logError(t)
                    }
                },
            )
        }

        /** The fragment goes away: the page goes with it, without leaving twice. */
        fun destroy() {
            leaving = true
            handle?.close()
        }

        fun reveal() {
            handle?.takeIf { !it.isOpen }?.reveal()
        }

        fun reload() = js("window.onReposChanged && window.onReposChanged()")

        private fun js(script: String) = handle?.runScript(script)

        private fun order(): List<String> = try {
            JSONArray(prefs.getString(ORDER, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (t: Throwable) {
            emptyList()
        }

        // ------------------------------------------------------------ what the page reads

        @JavascriptInterface
        fun list(): String {
            val out = JSONArray()
            val known = order()
            val rows = ArrayList<JSONObject>()
            for (r in RepositoryManager.getRepositories()) {
                if (AddonManager.isAddonUrl(r.url)) continue
                rows.add(JSONObject().put("url", r.url).put("name", r.name).put("icon", r.iconUrl ?: "")
                    .put("type", "cs").put("on", true).put("count", counts[r.url] ?: -1))
            }
            for (s in AddonManager.getSources()) {
                val type = typeOf(s.addonKind)
                val n = try { AddonManager.entriesOf(s).size } catch (t: Throwable) { 0 }
                rows.add(JSONObject().put("url", s.url).put("name", AddonManager.displayNameOf(s)).put("icon", s.iconUrl ?: "")
                    .put("type", type).put("on", kindOn(type)).put("count", if (type == "st") -2 else n))
            }
            // the order the user gave; new ones at the end
            rows.sortedBy { r -> known.indexOf(r.getString("url")).let { if (it < 0) Int.MAX_VALUE else it } }
                .filter { it.getString("url") !in pending.keys }
                .forEach { out.put(it) }
            return JSONObject().put("repos", out)
                .put("allowed", JSONArray(listOf("cs", "nv", "st", "sk").filter { kindOn(it) }))
                .put("stats", stats() ?: JSONObject.NULL)
                .put("repoList", com.lagradost.cloudstream3.fork.ForkFeatures.repoListShown(activity))
                .toString()
        }

        /** Counts of CloudStream repositories come later, as window.onRepoCount(url, n). */
        @JavascriptInterface
        fun loadCounts() {
            ioSafe {
                for (r in RepositoryManager.getRepositories()) {
                    if (counts.containsKey(r.url) || AddonManager.isAddonUrl(r.url)) continue
                    val n = try {
                        RepositoryManager.getRepoPlugins(r)?.size ?: 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        -1
                    }
                    if (n >= 0) counts[r.url] = n
                    js("window.onRepoCount && window.onRepoCount(${JSONObject.quote(r.url)}, $n)")
                }
            }
        }

        @JavascriptInterface
        fun setOrder(json: String?) {
            try {
                JSONArray(json ?: return)
                prefs.edit().putString(ORDER, json).apply()
            } catch (t: Throwable) {
            }
        }

        @JavascriptInterface
        fun clipboard(): String = try {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        } catch (t: Throwable) {
            ""
        }

        @JavascriptInterface
        fun separator(): String = RepoAdapter.SHAREABLE_REPO_SEPARATOR

        // ------------------------------------------------------------ going somewhere

        @JavascriptInterface
        fun open(url: String?) {
            url ?: return
            ui.post {
                val source = AddonManager.getSource(url)
                if (source != null) {
                    if (!kindOn(typeOf(source.addonKind))) return@post
                    // its own dialog comes over the page
                    AddonUi.open(activity, source) { onChanged(); reload() }
                    return@post
                }
                val repo = RepositoryManager.getRepositories().firstOrNull { it.url == url } ?: return@post
                handle?.hide()
                try {
                    fragment.findNavController().navigate(
                        R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                        PluginsFragment.newInstance(repo)
                    )
                } catch (t: Throwable) {
                    logError(t)
                    reveal()
                }
            }
        }

        @JavascriptInterface
        fun openPlugins() {
            ui.post {
                handle?.hide()
                try {
                    fragment.findNavController().navigate(
                        R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                        PluginsFragment.newLocalInstance(activity.getString(R.string.extensions))
                    )
                } catch (t: Throwable) {
                    logError(t)
                    reveal()
                }
            }
        }

        /** The search of all plugins: the old screen with its search open; closing it brings the page back. */
        @JavascriptInterface
        fun search() {
            ui.post {
                handle?.hide()
                search.invoke()
            }
        }

        @JavascriptInterface
        fun repoList() {
            ui.post {
                AssetPage.show(
                    activity, "Repolist.html",
                    onAddRepository = { url, name ->
                        js("window.addFromList && window.addFromList(${JSONObject.quote(url)}, ${JSONObject.quote(name ?: "")})")
                    },
                    addedUrls = { RepositoryManager.getRepositories().map { it.url } + AddonManager.sourceUrls() },
                    allowedTypes = { AddonUi.allowedTypes() },
                )
            }
        }

        // ------------------------------------------------------------ changing

        /** Deleted after a moment unless [undo] comes first; the page closing does it at once. */
        @JavascriptInterface
        fun remove(url: String?) {
            url ?: return
            val r = Runnable { pending.remove(url); delete(url) }
            pending.put(url, r)?.let { ui.removeCallbacks(it) }
            ui.postDelayed(r, 4500)
        }

        @JavascriptInterface
        fun undo(url: String?) {
            pending.remove(url ?: return)?.let { ui.removeCallbacks(it) }
        }

        private fun flush() {
            for ((url, r) in pending.entries.toList()) {
                ui.removeCallbacks(r)
                pending.remove(url)
                delete(url)
            }
        }

        private fun delete(url: String) {
            val ctx = activity.applicationContext
            ioSafe {
                try {
                    if (AddonManager.isAddonUrl(url)) AddonManager.remove(url)
                    else RepositoryManager.getRepositories().firstOrNull { it.url == url }
                        ?.let { RepositoryManager.removeRepository(ctx, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logError(t)
                }
                counts.remove(url)
                main { onChanged() }
            }
        }

        /** Adds what the sheet holds, like the old dialog; the answer is window.onRepoAdded(token, json). */
        @JavascriptInterface
        fun add(token: Int, input: String?, name: String?) {
            val urlInput = input?.trim().orEmpty()
            val custom = name?.trim()?.takeIf { it.isNotEmpty() }
            fun answer(ok: Boolean, message: String) =
                js("window.onRepoAdded && window.onRepoAdded($token, ${JSONObject.quote(JSONObject().put("ok", ok).put("message", message).toString())})")
            if (urlInput.isEmpty()) {
                answer(false, activity.getString(R.string.error_invalid_url))
                return
            }
            ioSafe {
                try {
                    val url = RepositoryManager.parseRepoUrl(urlInput)
                    var address = url ?: urlInput
                    if (url.isNullOrBlank()) AddonUi.resolveSkyStreamCode(urlInput)?.let { address = it }
                    // a kind the admin switched off is refused with its name
                    val detected = try {
                        AddonManager.detect(address)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        null
                    }
                    if (detected != null && !AddonManager.isKindEnabled(detected.kind)) {
                        answer(false, "${detected.kind.label} depoları şu an kapalı, eklenemez")
                        return@ioSafe
                    }
                    if (detected != null) {
                        when (AddonUi.tryAdd(activity, address, custom)) {
                            AddonUi.Outcome.ADDED -> {
                                main { onChanged() }
                                answer(true, "${custom ?: detected.name} eklendi")
                            }
                            else -> answer(false, "")
                        }
                        return@ioSafe
                    }
                    if (url.isNullOrBlank()) {
                        answer(false, activity.getString(R.string.error_invalid_data))
                        return@ioSafe
                    }
                    val repository = RepositoryManager.parseRepository(url)
                    if (repository == null) {
                        answer(false, activity.getString(R.string.no_repository_found_error))
                        return@ioSafe
                    }
                    val newRepo = RepositoryData(repository.iconUrl, custom ?: repository.name, url)
                    RepositoryManager.addRepository(newRepo)
                    main { onChanged() }
                    answer(true, "${newRepo.name} eklendi")
                    val plugins = RepositoryManager.getRepoPlugins(newRepo)
                    counts[newRepo.url] = plugins?.size ?: 0
                    js("window.onRepoCount && window.onRepoCount(${JSONObject.quote(newRepo.url)}, ${plugins?.size ?: 0})")
                    if (plugins.isNullOrEmpty()) {
                        answer(false, activity.getString(R.string.no_plugins_found_error))
                        return@ioSafe
                    }
                    main { activity.addRepositoryDialog(newRepo) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logError(t)
                    answer(false, activity.getString(R.string.error_invalid_data))
                }
            }
        }
    }
}
