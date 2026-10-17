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
    const val PLUGINS = 3

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
        else -> PLUGINS
    }
}
