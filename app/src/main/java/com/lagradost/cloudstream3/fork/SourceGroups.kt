package com.lagradost.cloudstream3.fork

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.addons.AddonKind
import com.lagradost.cloudstream3.addons.AddonManager

/**
 * The groups of the home page source list: Nuvio on top, then the IPTV lists, then the plugins.
 */
object SourceGroups {
    const val NUVIO = 1
    const val IPTV = 2
    const val FILES = 3
    const val PLUGINS = 4

    /** A plugin picked from the phone's files (Plus > Dosyadan eklentiler). */
    fun isFilePlugin(api: MainAPI?): Boolean = api?.sourcePlugin?.contains("/fork_file_plugins/") == true

    /** A source made by Plus > Site Kazıyıcı. */
    fun isScraper(api: MainAPI?): Boolean = com.lagradost.cloudstream3.fork.scraper.Scrapers.isScraper(api?.sourcePlugin)

    fun isNuvio(api: MainAPI?): Boolean {
        val tag = api?.sourcePlugin ?: return false
        return try {
            AddonManager.isBuiltInCatalogProvider(tag) || AddonManager.sourceOfProvider(tag)?.addonKind == AddonKind.NUVIO
        } catch (t: Throwable) {
            false
        }
    }

    fun groupOf(api: MainAPI?): Int = when {
        api == null -> 0
        isNuvio(api) -> NUVIO
        Iptv.isIptv(api) -> IPTV
        isFilePlugin(api) -> FILES
        else -> PLUGINS
    }
}
