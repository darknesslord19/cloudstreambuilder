package com.lagradost.cloudstream3.addons.js

import com.fasterxml.jackson.databind.JsonNode
import java.io.File

/**
 * One plugin script the engine can run.
 *
 * @property key unique and stable name of this exact script. It must change when the script
 * changes, because the engine keeps a loaded script in memory under this key.
 * @property sourceFile the JavaScript file, read when the plugin is first used.
 * @property env values handed to the script environment, must contain "flavor"
 * ("nuvio" or "skystream").
 */
class JsPlugin(
    val key: String,
    val sourceFile: File,
    val env: Map<String, Any?>,
) {
    /**
     * SkyStream plugins are providers the user browses, they stay loaded between calls.
     * Nuvio scrapers answer one question each, and Nuvio itself starts them fresh every time,
     * so they are unloaded as soon as they are done.
     */
    val keepLoaded: Boolean get() = env["flavor"] != FLAVOR_NUVIO

    companion object {
        const val FLAVOR_NUVIO = "nuvio"
        const val FLAVOR_SKYSTREAM = "skystream"
    }
}

/** Runs the JavaScript plugins of Nuvio and SkyStream repositories. */
interface AddonJsEngine {
    /**
     * Calls [function] of [plugin] with [args] and waits for its result.
     *
     * @return the result as a JSON tree, null if the function gave no value.
     * @throws com.lagradost.cloudstream3.addons.AddonException if the plugin fails or
     * does not answer within [timeoutMs].
     */
    suspend fun call(
        plugin: JsPlugin,
        function: String,
        args: List<Any?>,
        timeoutMs: Long,
    ): JsonNode?

    /** Frees the memory held by a plugin, it is loaded again the next time it is called. */
    fun unload(pluginKey: String)

    /** Frees the memory held by every plugin. */
    fun unloadAll()
}
