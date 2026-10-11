package com.lagradost.cloudstream3.ui.home

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.extensions.AddonUi

/**
 * The settings a provider has, so they can be reached from the list of providers on the
 * home page and not only from the list of plugins in the settings.
 */
object ProviderSettings {
    /**
     * @return what opens the settings of the plugin or add-on [api] comes from,
     * null if there is nothing to set.
     */
    fun actionFor(context: Context, api: MainAPI?): (() -> Unit)? {
        val source = api?.sourcePlugin ?: return null
        return try {
            // Stremio, Nuvio and SkyStream sources
            AddonUi.settingsActionFor(context, source)?.let { return it }

            // CloudStream plugins say themselves if they have settings
            val plugin = (PluginManager.plugins[source] ?: PluginManager.urlPlugins[source]) as? Plugin
            val open = plugin?.openSettings ?: return null
            return {
                try {
                    open.invoke(context)
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }
}
