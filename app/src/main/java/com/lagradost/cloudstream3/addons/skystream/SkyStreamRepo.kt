package com.lagradost.cloudstream3.addons.skystream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.addons.AddonEntry
import com.lagradost.cloudstream3.addons.AddonException
import com.lagradost.cloudstream3.addons.AddonHttp
import com.lagradost.cloudstream3.addons.int
import com.lagradost.cloudstream3.addons.list
import com.lagradost.cloudstream3.addons.parseJsonTree
import com.lagradost.cloudstream3.addons.strings
import com.lagradost.cloudstream3.addons.text
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Reading of SkyStream repositories. A repository is a repo.json that points to one or more
 * lists of plugins, a plugin is a ".sky" file: a zip with a plugin.json and a plugin.js.
 */
internal object SkyStreamRepo {
    private const val MAX_LISTS = 24
    private const val MAX_NESTED_REPOS = 12
    private const val MAX_UNPACKED_BYTES = 12 * 1024 * 1024

    /** A downloaded plugin: its manifest (plugin.json) and its script. */
    class Installed(val manifest: JsonNode, val script: String)

    /**
     * If [json] may be the repo.json of a SkyStream repository. A CloudStream repository looks
     * very similar, the difference is that SkyStream ones always name a package.
     * [collectPlugins] has the final say.
     */
    fun looksLikeRepo(json: JsonNode?): Boolean {
        if (json == null || !json.isObject) return false
        if (json.text("packageName", "id") == null) return false
        return json.list("pluginLists").isNotEmpty() || json.list("repos").isNotEmpty() ||
                json.list("plugins").isNotEmpty()
    }

    fun isPlugin(node: JsonNode?): Boolean {
        if (node == null || !node.isObject) return false
        return node.text("packageName") != null && node.text("url") != null
    }

    fun toEntry(plugin: JsonNode): AddonEntry {
        val id = plugin.text("packageName") ?: ""
        // Status 0 means the author has marked the plugin as broken
        return AddonEntry(id, plugin.text("name") ?: id, plugin.text("description"), plugin.int("status") != 0)
    }

    /**
     * Every plugin of a repository: the ones written into the repo.json, the ones in its
     * plugin lists, and the ones of the repositories it includes.
     */
    suspend fun collectPlugins(repo: JsonNode, rewriteUrl: (String) -> String): List<JsonNode> {
        val plugins = LinkedHashMap<String, JsonNode>()
        var listsLeft = MAX_LISTS
        var reposLeft = MAX_NESTED_REPOS

        // The first description of a package wins
        fun keep(plugin: JsonNode) {
            val id = plugin.text("packageName") ?: return
            if (isPlugin(plugin) && !plugins.containsKey(id)) plugins[id] = plugin
        }

        suspend fun visit(node: JsonNode, depth: Int) {
            for (plugin in node.list("plugins")) {
                keep(plugin)
            }
            for (listUrl in node.strings("pluginLists")) {
                if (listsLeft-- <= 0) return
                val list = AddonHttp.getJsonOrNull(rewriteUrl(listUrl)) ?: continue
                if (!list.isArray) continue
                for (plugin in list) {
                    keep(plugin)
                }
            }
            if (depth >= 2) return
            for (repoUrl in node.strings("repos")) {
                if (reposLeft-- <= 0) return
                val nested = AddonHttp.getJsonOrNull(rewriteUrl(repoUrl)) ?: continue
                if (nested.isObject) visit(nested, depth + 1)
            }
        }

        visit(repo, 0)
        return plugins.values.toList()
    }

    /** Downloads the file of [plugin] and takes its manifest and script out of it. */
    suspend fun download(plugin: JsonNode, rewriteUrl: (String) -> String): Installed {
        val url = plugin.text("url") ?: throw AddonException("The plugin has no download url")
        val bytes = AddonHttp.getBytes(rewriteUrl(url))
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) {
            // A plain .js file, the entry of the plugin list is its manifest
            return Installed(plugin, bytes.toString(Charsets.UTF_8))
        }

        var manifestText: String? = null
        var script: String? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val fileName = entry.name.replace('\\', '/').substringAfterLast('/')
                    val wanted = !entry.isDirectory && (fileName == "plugin.json" || fileName == "plugin.js")
                    // Files in the root of the archive win over ones in a sub folder
                    val isRoot = !entry.name.replace('\\', '/').trimStart('/').contains('/')
                    if (wanted) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_UNPACKED_BYTES) throw AddonException("The plugin is too large")
                            output.write(buffer, 0, read)
                        }
                        val text = output.toByteArray().toString(Charsets.UTF_8)
                        if (fileName == "plugin.json") {
                            if (manifestText == null || isRoot) manifestText = text
                        } else {
                            if (script == null || isRoot) script = text
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddonException) {
            throw e
        } catch (t: Throwable) {
            throw AddonException("The plugin file could not be opened: ${t.message}")
        }

        val code = script ?: throw AddonException("The plugin file has no plugin.js")
        // The plugin.json inside the file describes exactly this build, values missing there
        // (like the download url) are kept from the plugin list.
        val manifest = (plugin as? ObjectNode)?.deepCopy() ?: throw AddonException("Invalid plugin entry")
        val packed = parseJsonTree(manifestText)
        if (packed is ObjectNode) manifest.setAll<JsonNode>(packed)
        return Installed(manifest, code)
    }
}
