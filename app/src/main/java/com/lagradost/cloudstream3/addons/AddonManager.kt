package com.lagradost.cloudstream3.addons

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.addons.js.JsPlugin
import com.lagradost.cloudstream3.addons.nuvio.NuvioRepo
import com.lagradost.cloudstream3.addons.nuvio.NuvioScraper
import com.lagradost.cloudstream3.addons.skystream.SkyStreamProvider
import com.lagradost.cloudstream3.addons.skystream.SkyStreamRepo
import com.lagradost.cloudstream3.addons.stremio.StremioAddon
import com.lagradost.cloudstream3.addons.stremio.StremioProvider
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps track of the Stremio, Nuvio and SkyStream sources the user has added, and turns them
 * into things the rest of the app understands: providers in [APIHolder] for everything that
 * has a catalog, and stream sources in [AddonRegistry] for everything that only finds links.
 *
 * A source is identified by its url. What the user added is saved as a small list, the
 * manifests and scripts are kept on disk so the sources work at start without any network.
 */
object AddonManager {
    private const val TAG = "AddonManager"
    private const val SOURCES_KEY = "EXTERNAL_ADDON_SOURCES"
    private const val REFRESHED_KEY_PREFIX = "addon_refreshed/"
    private const val TAG_PREFIX = "external-addon:"
    private const val REFRESH_AFTER_MS = 12L * 60L * 60L * 1000L

    /**
     * Manifest of the catalog add-on that is used when the user has none: it is added next
     * to the first stream source, and asked for the details of titles nothing else knows.
     */
    @Volatile
    internal var fallbackCatalogUrl: String = StremioAddon.CINEMETA_MANIFEST

    /** What [detect] found behind a url. */
    class Detected internal constructor(
        val kind: AddonKind,
        val url: String,
        val name: String,
        val description: String?,
        val iconUrl: String?,
        /** The scrapers or plugins the user can pick from, empty for Stremio add-ons. */
        val entries: List<AddonEntry>,
        internal val manifestText: String,
        internal val pluginsText: String?,
    )

    /** Outcome of adding a source or changing what is switched on in it. */
    class Result(
        val source: AddonSource,
        /** Names of the entries that could not be downloaded. */
        val failed: List<String>,
        /** True if the Cinemeta catalog was added so the new stream source has something to list. */
        val addedCatalog: Boolean,
    )

    @Volatile
    private var platformOrNull: AddonPlatform? = null

    private val platform: AddonPlatform
        get() = platformOrNull ?: throw AddonException("Add-ons are not initialised yet")

    val isInitialised: Boolean get() = platformOrNull != null

    /** Changes to the list of sources and to what is registered happen one at a time. */
    private val mutex = Mutex()

    private val nuvioBySource = ConcurrentHashMap<String, List<NuvioScraper>>()
    private val stremioBySource = ConcurrentHashMap<String, StremioAddon>()
    private val scriptKeysBySource = ConcurrentHashMap<String, Set<String>>()

    fun init(platform: AddonPlatform) {
        platformOrNull = platform
    }

    // ------------------------------------------------------------ the saved list

    fun getSources(): List<AddonSource> {
        val text = platformOrNull?.getValue(SOURCES_KEY) ?: return emptyList()
        return try {
            mapper.readValue(text, Array<AddonSource>::class.java).filter { it.addonKind != null }
        } catch (t: Throwable) {
            Log.e(TAG, "The saved add-on list could not be read: ${t.message}")
            emptyList()
        }
    }

    private fun saveSources(sources: List<AddonSource>) {
        platform.setValue(SOURCES_KEY, sources.toJsonText())
    }

    fun getSource(url: String): AddonSource? = getSources().firstOrNull { it.url == url }

    fun isAddonUrl(url: String?): Boolean = url != null && getSources().any { it.url == url }

    /** The urls of all saved sources. */
    fun sourceUrls(): Set<String> = getSources().map { it.url }.toSet()

    // ------------------------------------------------------------ files

    private class Store(root: File, url: String) {
        val dir = File(root, sha1(url).take(20))
        val manifestFile = File(dir, "manifest.json")
        val pluginsFile = File(dir, "plugins.json")
        val scriptsDir = File(dir, "scripts")

        /** File for one version of one script, [extension] is "js" or "json". */
        fun scriptFile(id: String, version: String, extension: String): File {
            val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            return File(scriptsDir, safe + "-" + sha1("$id@$version").take(10) + "." + extension)
        }
    }

    private fun storeOf(url: String) = Store(platform.storageDir, url)

    private fun sha1(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Writes through a temporary file, so a crash never leaves half a file behind. */
    private fun writeFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun readJson(file: File): JsonNode? {
        return try {
            if (file.exists()) parseJsonTree(file.readText(Charsets.UTF_8)) else null
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ detection

    /**
     * Works out what [input] points to. It may be the url of a Stremio manifest.json, of a
     * Nuvio manifest.json or of a SkyStream repo.json, the folder such a file lives in, or a
     * GitHub repository page.
     *
     * @return null if it is none of those, for example a normal CloudStream repository.
     */
    suspend fun detect(input: String): Detected? {
        val platform = platform
        for (url in candidateUrls(input)) {
            val text = try {
                AddonHttp.getText(platform.rewriteUrl(url))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                continue
            }
            val json = parseJsonTree(text) ?: continue
            classify(url, json, text)?.let { return it }
        }
        return null
    }

    internal fun candidateUrls(input: String): List<String> {
        var url = input.trim()
        if (url.isEmpty()) return emptyList()
        val lower = url.lowercase()
        if (lower.startsWith("stremio://")) {
            url = "https://" + url.substring("stremio://".length)
        } else if (lower.startsWith("skystream://")) {
            url = "https://" + url.substring("skystream://".length)
        } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // Another scheme, or a short code without any dot: not for us
            if (url.contains("://") || !url.contains('.') || url.contains(' ')) return emptyList()
            url = "https://$url"
        }

        val candidates = LinkedHashSet<String>()
        val path = url.substringBefore('#').substringBefore('?').trimEnd('/')
        val github = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/]+?)(?:\.git)?$""").find(path)
        when {
            github != null -> {
                val raw = "https://raw.githubusercontent.com/${github.groupValues[1]}/${github.groupValues[2]}/HEAD"
                candidates.add("$raw/manifest.json")
                candidates.add("$raw/repo.json")
            }

            path.endsWith(".json", ignoreCase = true) -> candidates.add(url)

            // The configuration page of a Stremio add-on sits next to its manifest
            path.endsWith("/configure") -> candidates.add(path.removeSuffix("/configure") + "/manifest.json")

            else -> {
                candidates.add("$path/manifest.json")
                candidates.add("$path/repo.json")
                candidates.add(url)
            }
        }
        return candidates.toList()
    }

    private suspend fun classify(url: String, json: JsonNode, text: String): Detected? {
        if (NuvioRepo.isManifest(json)) {
            val scrapers = NuvioRepo.scrapers(json)
            if (scrapers.isEmpty()) return null
            return Detected(
                AddonKind.NUVIO, url, json.text("name") ?: "Nuvio", json.text("description"),
                json.text("logo", "icon"), scrapers.map { it.toEntry() }, text, null,
            )
        }
        if (StremioAddon.isManifest(json)) {
            val addon = StremioAddon(url, json)
            return Detected(AddonKind.STREMIO, url, addon.name, addon.description, addon.logo, emptyList(), text, null)
        }
        if (SkyStreamRepo.looksLikeRepo(json)) {
            val plugins = SkyStreamRepo.collectPlugins(json) { platform.rewriteUrl(it) }
            if (plugins.isEmpty()) return null
            return Detected(
                AddonKind.SKYSTREAM, url, json.text("name") ?: "SkyStream", json.text("description"),
                json.text("iconUrl"), plugins.map { SkyStreamRepo.toEntry(it) }, text, plugins.toJsonText(),
            )
        }
        return null
    }

    // ------------------------------------------------------------ add, change, remove

    /**
     * Adds what [detect] found, or replaces the source if it was added before.
     *
     * @param customName name to show instead of the one the source gives itself.
     * @param enabledIds the scrapers or plugins to switch on, ignored for Stremio add-ons.
     */
    suspend fun add(detected: Detected, customName: String?, enabledIds: List<String>): Result {
        val source: AddonSource
        val failed: List<String>
        mutex.withLock {
            val valid = detected.entries.map { it.id }.toSet()
            source = AddonSource(
                kind = detected.kind.id,
                url = detected.url,
                name = customName?.trim()?.takeIf { it.isNotEmpty() } ?: detected.name,
                iconUrl = detected.iconUrl,
                description = detected.description,
                enabled = enabledIds.filter { valid.contains(it) }.distinct(),
            )
            val store = storeOf(source.url)
            writeFile(store.manifestFile, detected.manifestText)
            detected.pluginsText?.let { writeFile(store.pluginsFile, it) }
            failed = install(source)
            saveSources(getSources().filter { it.url != source.url } + source)
            activate(source)
            markRefreshed(source.url)
        }
        val addedCatalog = ensureCatalog()
        platform.providersChanged()
        return Result(source, failed, addedCatalog)
    }

    /** Changes which scrapers or plugins of a source are switched on. */
    suspend fun setEnabled(url: String, enabledIds: List<String>): Result? {
        val updated: AddonSource
        val failed: List<String>
        mutex.withLock {
            val current = getSource(url) ?: return null
            val valid = entriesOf(current).map { it.id }.toSet()
            updated = current.copy(enabled = enabledIds.filter { valid.contains(it) }.distinct())
            failed = install(updated)
            saveSources(getSources().map { if (it.url == url) updated else it })
            activate(updated)
        }
        val addedCatalog = ensureCatalog()
        platform.providersChanged()
        return Result(updated, failed, addedCatalog)
    }

    suspend fun remove(url: String) {
        mutex.withLock {
            deactivate(url)
            saveSources(getSources().filter { it.url != url })
            platform.setValue(REFRESHED_KEY_PREFIX + sha1(url), null)
            try {
                storeOf(url).dir.deleteRecursively()
            } catch (t: Throwable) {
                Log.w(TAG, "Could not delete the files of $url: ${t.message}")
            }
        }
        platform.providersChanged()
    }

    /** The scrapers or plugins of a source, read from what was last downloaded. */
    fun entriesOf(source: AddonSource): List<AddonEntry> {
        val store = storeOf(source.url)
        return when (source.addonKind) {
            AddonKind.NUVIO -> NuvioRepo.scrapers(readJson(store.manifestFile)).map { it.toEntry() }
            AddonKind.SKYSTREAM -> skyPlugins(store).map { SkyStreamRepo.toEntry(it) }
            else -> emptyList()
        }
    }

    /**
     * What a Stremio add-on offers, as the resource names of its manifest:
     * any of "catalog", "meta", "stream" and "subtitles".
     */
    fun capabilitiesOf(source: AddonSource): List<String> {
        val addon = stremioBySource[source.url] ?: return emptyList()
        val parts = ArrayList<String>()
        if (addon.catalogs.isNotEmpty()) parts.add("catalog")
        if (addon.hasResource("meta")) parts.add("meta")
        if (addon.hasResource("stream")) parts.add("stream")
        if (addon.hasResource("subtitles")) parts.add("subtitles")
        return parts
    }

    /** The page where a Stremio add-on can be set up, if it has one. */
    fun configureUrlOf(source: AddonSource): String? = stremioBySource[source.url]?.configureUrl

    private fun skyPlugins(store: Store): List<JsonNode> {
        val json = readJson(store.pluginsFile) ?: return emptyList()
        return if (json.isArray) json.filter { SkyStreamRepo.isPlugin(it) } else emptyList()
    }

    /**
     * Downloads the scripts of the entries that are switched on and not on disk yet.
     * @return the names of the entries that failed.
     */
    private suspend fun install(source: AddonSource): List<String> {
        val store = storeOf(source.url)
        val platform = platform
        val wanted = source.enabled.toSet()
        return when (source.addonKind) {
            AddonKind.NUVIO -> NuvioRepo.scrapers(readJson(store.manifestFile))
                .filter { wanted.contains(it.id) }
                .amap { scraper ->
                    val file = store.scriptFile(scraper.id, scraper.version, "js")
                    if (file.exists()) return@amap null
                    try {
                        val script = AddonHttp.getBytes(platform.rewriteUrl(NuvioRepo.scriptUrl(source.url, scraper)))
                        writeFile(file, script.toString(Charsets.UTF_8))
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "Download of ${scraper.name} failed: ${t.message}")
                        scraper.name
                    }
                }.filterNotNull()

            AddonKind.SKYSTREAM -> skyPlugins(store)
                .filter { wanted.contains(it.text("packageName")) }
                .amap { plugin ->
                    val id = plugin.text("packageName") ?: return@amap null
                    val version = plugin.text("version") ?: "0"
                    val scriptFile = store.scriptFile(id, version, "js")
                    val manifestFile = store.scriptFile(id, version, "json")
                    if (scriptFile.exists() && manifestFile.exists()) return@amap null
                    try {
                        val installed = SkyStreamRepo.download(plugin) { platform.rewriteUrl(it) }
                        writeFile(manifestFile, installed.manifest.toJsonText())
                        writeFile(scriptFile, installed.script)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "Download of $id failed: ${t.message}")
                        plugin.text("name") ?: id
                    }
                }.filterNotNull()

            else -> emptyList()
        }
    }

    // ------------------------------------------------------------ registration

    private fun tagOf(url: String) = TAG_PREFIX + url

    private fun register(provider: MainAPI, tag: String) {
        provider.sourcePlugin = tag
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)
    }

    private fun unregister(tag: String) {
        APIHolder.apis.filter { it.sourcePlugin == tag }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { it.sourcePlugin == tag }
        }
    }

    /** A provider name that is not taken by another provider yet. */
    private fun uniqueName(wanted: String, kind: AddonKind, tag: String, alsoTaken: Collection<String>): String {
        val taken = HashSet<String>(alsoTaken)
        APIHolder.allProviders.withLock {
            for (provider in APIHolder.allProviders) {
                if (provider.sourcePlugin != tag) taken.add(provider.name)
            }
        }
        val base = wanted.trim().ifEmpty { kind.label }
        if (!taken.contains(base)) return base
        val labelled = "$base (${kind.label})"
        if (!taken.contains(labelled)) return labelled
        var number = 2
        while (taken.contains("$labelled $number")) number++
        return "$labelled $number"
    }

    private fun deactivate(url: String) {
        unregister(tagOf(url))
        stremioBySource.remove(url)
        nuvioBySource.remove(url)
        publishRegistry()
        scriptKeysBySource.remove(url)?.forEach { key ->
            try {
                platform.jsEngine.unload(key)
            } catch (t: Throwable) {
                Log.w(TAG, "Unload failed: ${t.message}")
            }
        }
    }

    private fun publishRegistry() {
        AddonRegistry.stremio = stremioBySource.values.toList()
        AddonRegistry.nuvio = nuvioBySource.values.flatten()
    }

    /** Registers everything [source] offers, from the files on disk. Uses no network. */
    private fun activate(source: AddonSource) {
        val platform = platform
        val url = source.url
        val tag = tagOf(url)
        val store = storeOf(url)
        val previousKeys = scriptKeysBySource[url] ?: emptySet()
        val keys = HashSet<String>()

        unregister(tag)
        stremioBySource.remove(url)
        nuvioBySource.remove(url)

        when (source.addonKind) {
            AddonKind.STREMIO -> {
                val manifest = readJson(store.manifestFile)
                if (manifest != null && StremioAddon.isManifest(manifest)) {
                    val addon = StremioAddon(url, manifest)
                    stremioBySource[url] = addon
                    if (addon.catalogs.isNotEmpty()) {
                        val name = uniqueName(source.name, AddonKind.STREMIO, tag, emptyList())
                        register(StremioProvider(addon, name, platform.defaultProviderLanguage(), platform.uiLanguage()), tag)
                    }
                }
            }

            AddonKind.NUVIO -> {
                val wanted = source.enabled.toSet()
                val scrapers = NuvioRepo.scrapers(readJson(store.manifestFile))
                    .filter { wanted.contains(it.id) }
                    .mapNotNull { info ->
                        val file = store.scriptFile(info.id, info.version, "js")
                        if (!file.exists()) return@mapNotNull null
                        val key = "nuvio|" + sha1(url).take(12) + "|" + info.id + "|" + info.version
                        keys.add(key)
                        val plugin = JsPlugin(
                            key, file, mapOf(
                                "flavor" to JsPlugin.FLAVOR_NUVIO,
                                "id" to info.id,
                                "settings" to emptyMap<String, Any>(),
                                "tmdbApiKey" to StreamHub.TMDB_API_KEY,
                            )
                        )
                        NuvioScraper(info.id, info.name, info.supportedTypes, plugin) { platform.jsEngine }
                    }
                if (scrapers.isNotEmpty()) nuvioBySource[url] = scrapers
            }

            AddonKind.SKYSTREAM -> {
                val wanted = source.enabled.toSet()
                val names = ArrayList<String>()
                for (listed in skyPlugins(store)) {
                    val id = listed.text("packageName") ?: continue
                    if (!wanted.contains(id)) continue
                    val version = listed.text("version") ?: "0"
                    val scriptFile = store.scriptFile(id, version, "js")
                    val manifest = readJson(store.scriptFile(id, version, "json")) as? ObjectNode
                    if (!scriptFile.exists() || manifest == null) continue
                    val keyBase = "sky|" + sha1(url).take(12) + "|" + id + "|" + version

                    // One script can serve several feeds, each becomes a provider of its own
                    val subProviders = manifest.list("providers").filter { it.text("id") != null }
                    if (subProviders.isEmpty()) {
                        val name = uniqueName(manifest.text("name") ?: id, AddonKind.SKYSTREAM, tag, names)
                        names.add(name)
                        keys.add(keyBase)
                        register(skyProvider(manifest, name, keyBase, scriptFile, id), tag)
                    } else {
                        for (sub in subProviders) {
                            val subId = sub.text("id") ?: continue
                            val subManifest = manifest.deepCopy()
                            sub.text("baseUrl")?.let { subManifest.put("baseUrl", it) }
                            subManifest.put("providerId", subId)
                            val name = uniqueName(sub.text("name") ?: subId, AddonKind.SKYSTREAM, tag, names)
                            names.add(name)
                            val key = "$keyBase|$subId"
                            keys.add(key)
                            register(skyProvider(subManifest, name, key, scriptFile, id), tag)
                        }
                    }
                }
            }

            null -> {}
        }

        publishRegistry()
        scriptKeysBySource[url] = keys
        // Scripts of versions that are no longer used can leave the memory
        (previousKeys - keys).forEach { key ->
            try {
                platform.jsEngine.unload(key)
            } catch (t: Throwable) {
                Log.w(TAG, "Unload failed: ${t.message}")
            }
        }
    }

    private fun skyProvider(
        manifest: ObjectNode,
        name: String,
        key: String,
        scriptFile: File,
        packageName: String,
    ): SkyStreamProvider {
        val platform = platform
        val plugin = JsPlugin(
            key, scriptFile, mapOf(
                "flavor" to JsPlugin.FLAVOR_SKYSTREAM,
                "manifest" to manifest,
                "storageNamespace" to packageName,
                "language" to platform.defaultProviderLanguage(),
            )
        )
        return SkyStreamProvider(manifest, name, plugin) { platform.jsEngine }
    }

    /**
     * Stream sources only find links for a title, they have no list of titles themselves.
     * If the user has such sources but nothing with a catalog, the official Cinemeta catalog
     * of Stremio is added, otherwise there would be nothing to browse.
     */
    private suspend fun ensureCatalog(): Boolean {
        val hasStreamSource = AddonRegistry.nuvio.isNotEmpty() ||
                AddonRegistry.stremio.any { it.hasResource("stream") }
        val hasCatalog = AddonRegistry.stremio.any { it.catalogs.isNotEmpty() }
        if (!hasStreamSource || hasCatalog) return false
        if (isAddonUrl(fallbackCatalogUrl)) return false
        return try {
            val cinemeta = detect(fallbackCatalogUrl) ?: return false
            add(cinemeta, null, emptyList())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta could not be added: ${t.message}")
            false
        }
    }

    // ------------------------------------------------------------ start up and updates

    /** Registers every saved source from disk. Fast and without network, meant for app start. */
    suspend fun loadAll() {
        if (!isInitialised) return
        mutex.withLock {
            for (source in getSources()) {
                try {
                    activate(source)
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not load ${source.url}: ${t.message}")
                }
            }
        }
    }

    private fun markRefreshed(url: String) {
        platform.setValue(REFRESHED_KEY_PREFIX + sha1(url), System.currentTimeMillis().toString())
    }

    /**
     * Downloads the manifest of a source again, and with it new versions of its scripts.
     * @return false if the source could not be reached, in which case nothing changes.
     */
    suspend fun refresh(url: String): Boolean {
        val source = getSource(url) ?: return false
        val detected = try {
            detect(url)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        if (detected == null || detected.kind != source.addonKind) return false
        mutex.withLock {
            val current = getSource(url) ?: return false
            val store = storeOf(url)
            writeFile(store.manifestFile, detected.manifestText)
            detected.pluginsText?.let { writeFile(store.pluginsFile, it) }
            // Entries that were removed from the repository are dropped from the selection
            val valid = detected.entries.map { it.id }.toSet()
            val updated = if (current.addonKind == AddonKind.STREMIO) current
            else current.copy(enabled = current.enabled.filter { valid.contains(it) })
            install(updated)
            if (updated != current) saveSources(getSources().map { if (it.url == url) updated else it })
            activate(updated)
            cleanScripts(updated, store)
            markRefreshed(url)
        }
        return true
    }

    /** Deletes downloaded scripts that belong to versions or entries no longer in use. */
    private fun cleanScripts(source: AddonSource, store: Store) {
        val keep = HashSet<String>()
        val wanted = source.enabled.toSet()
        when (source.addonKind) {
            AddonKind.NUVIO -> NuvioRepo.scrapers(readJson(store.manifestFile))
                .filter { wanted.contains(it.id) }
                .forEach { keep.add(store.scriptFile(it.id, it.version, "js").name) }

            AddonKind.SKYSTREAM -> skyPlugins(store).forEach { plugin ->
                val id = plugin.text("packageName") ?: return@forEach
                if (!wanted.contains(id)) return@forEach
                val version = plugin.text("version") ?: "0"
                keep.add(store.scriptFile(id, version, "js").name)
                keep.add(store.scriptFile(id, version, "json").name)
            }

            else -> return
        }
        store.scriptsDir.listFiles()?.forEach { file ->
            if (!keep.contains(file.name)) file.delete()
        }
    }

    /** Refreshes the sources that were not refreshed for a while. Meant to run in the background. */
    suspend fun refreshStale(): Boolean {
        if (!isInitialised) return false
        var changed = false
        val now = System.currentTimeMillis()
        for (source in getSources()) {
            val last = platform.getValue(REFRESHED_KEY_PREFIX + sha1(source.url))?.toLongOrNull() ?: 0L
            // After a restored backup the list is there but the downloaded files are not
            val filesMissing = !storeOf(source.url).manifestFile.exists()
            if (!filesMissing && now - last < REFRESH_AFTER_MS) continue
            try {
                if (refresh(source.url)) changed = true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Refresh of ${source.url} failed: ${t.message}")
            }
        }
        if (changed) platform.providersChanged()
        return changed
    }
}
