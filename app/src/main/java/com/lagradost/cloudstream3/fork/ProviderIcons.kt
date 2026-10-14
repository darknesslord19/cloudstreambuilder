package com.lagradost.cloudstream3.fork

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The icons of the plugins, for the list of home page sources. A source only knows the file of
 * its plugin, the icon is in the list of its repository, so the icons are kept here by the
 * address of the plugin and read again from the repositories now and then.
 */
object ProviderIcons {
    private const val PREFS = "fork_provider_icons"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L

    /** Address of a plugin -> address of its icon */
    private val icons = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    @Volatile
    private var lastRefresh = 0L

    private fun load(context: Context) {
        if (loaded) return
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                (value as? String)?.let { icons[key] = it }
            }
        } catch (t: Throwable) {
            // Nothing kept yet
        }
        loaded = true
    }

    /** Plugin file -> address of the plugin, read once for a list. */
    fun pluginUrls(): Map<String, String> = try {
        PluginManager.getPluginsOnline().mapNotNull { data -> data.url?.let { data.filePath to it } }.toMap()
    } catch (t: Throwable) {
        emptyMap()
    }

    /** The icon of [api], null when its plugin has none or it is not known yet. */
    fun iconFor(context: Context, api: MainAPI?, urls: Map<String, String>): String? {
        val file = api?.sourcePlugin ?: return null
        load(context)
        val url = urls[file] ?: return null
        return icons[url]?.replace("%size%", "128")?.replace("%exact_size%", "128")
    }

    fun needsRefresh(): Boolean = System.currentTimeMillis() - lastRefresh > REFRESH_MS

    /** Reads the icons from every added repository. @return true when something new came. */
    suspend fun refresh(context: Context): Boolean {
        load(context)
        lastRefresh = System.currentTimeMillis()
        var changed = false
        val found = HashMap<String, String>()
        for (repository in RepositoryManager.getRepositories()) {
            try {
                RepositoryManager.getRepoPlugins(repository)?.forEach { wrapper ->
                    val icon = wrapper.plugin.iconUrl
                    if (!icon.isNullOrBlank()) found[wrapper.plugin.url] = icon
                }
            } catch (t: Throwable) {
                // A repository that cannot be read keeps its old icons
            }
        }
        found.forEach { (url, icon) ->
            if (icons[url] != icon) {
                icons[url] = icon
                changed = true
            }
        }
        if (changed) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                found.forEach { (url, icon) -> putString(url, icon) }
            }.apply()
        }
        return changed
    }

    /** Two letters for a source without an icon, "!" in front of a name only orders the list. */
    fun letters(name: String): String = displayName(name).filter { it.isLetterOrDigit() }.take(2).ifEmpty { "?" }

    fun displayName(name: String): String = name.trimStart('!', ' ').ifEmpty { name }

    /** A calm colour of its own for the letters of a source. */
    fun colorFor(name: String): Int {
        var h = 0
        for (c in name) h = (h * 29 + c.code) % 360
        return android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.45f, 0.55f))
    }

    /** What a source offers, in short words for the small labels. */
    fun typeLabels(api: MainAPI): List<String> {
        val types = api.supportedTypes
        val labels = ArrayList<String>()
        if (types.any { it == TvType.Movie || it == TvType.Documentary || it == TvType.Torrent }) labels.add("Film")
        if (types.any { it == TvType.TvSeries || it == TvType.AsianDrama || it == TvType.Cartoon }) labels.add("Dizi")
        if (types.any { it == TvType.Anime || it == TvType.AnimeMovie || it == TvType.OVA }) labels.add("Anime")
        if (types.contains(TvType.Live)) labels.add("Canlı")
        return labels
    }
}
