package com.lagradost.cloudstream3.addons.android

import android.content.Context
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.addons.AddonManager
import com.lagradost.cloudstream3.addons.AddonPlatform
import com.lagradost.cloudstream3.addons.js.AddonHostBridge
import com.lagradost.cloudstream3.addons.js.AddonJsEngine
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import java.io.File
import java.util.Locale

/** What the add-on code needs from the app, see [AddonPlatform]. */
class AndroidAddonPlatform(context: Context) : AddonPlatform {
    companion object {
        /** How the catalog that comes with the stream sources is shown in this build. */
        const val CATALOG_NAME = "DarknesNuvio"
        const val CATALOG_LANGUAGE = "tr"
    }

    private val appContext: Context = context.applicationContext ?: context

    private val bridge = AddonHostBridge(
        readAsset = { name -> readAsset(name) },
        readPreference = { key -> getValue(key) },
        writePreference = { key, value -> setValue(key, value) },
    )

    /** Created on first use, as a WebView is not cheap and many users will never need it. */
    private val engine: AddonJsEngine by lazy {
        WebViewJsEngine(appContext, bridge) { name -> readAsset(name) }
    }

    override val jsEngine: AddonJsEngine get() = engine

    override val storageDir: File = File(appContext.filesDir, "external_addons")

    // Stored with the other app data, so the list of sources is part of the app's backups
    override fun getValue(key: String): String? = CloudStreamApp.getKey<String>(key)

    override fun setValue(key: String, value: String?) {
        if (value == null) CloudStreamApp.removeKey(key) else CloudStreamApp.setKey(key, value)
    }

    override fun readAsset(name: String): String {
        return appContext.assets.open("addons/$name").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun rewriteUrl(url: String): String {
        return try {
            // Honours the "use jsDelivr for GitHub" setting, like normal repositories do
            RepositoryManager.convertRawGitUrl(url)
        } catch (t: Throwable) {
            url
        }
    }

    override fun defaultProviderLanguage(): String {
        val active = try {
            appContext.getApiProviderLangSettings()
        } catch (t: Throwable) {
            return "en"
        }
        if (active.contains(AllLanguagesName) || active.contains("en")) return "en"
        return active.firstOrNull() ?: "en"
    }

    override fun catalogDisplayName(): String = CATALOG_NAME

    override fun catalogLanguage(): String? {
        val active = try {
            appContext.getApiProviderLangSettings()
        } catch (t: Throwable) {
            return null
        }
        // Only when Turkish providers are shown, the catalog would be hidden otherwise
        return if (active.contains(AllLanguagesName) || active.contains(CATALOG_LANGUAGE)) CATALOG_LANGUAGE else null
    }

    override fun uiLanguage(): String {
        val chosen = try {
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(appContext.getString(R.string.locale_key), null)
        } catch (t: Throwable) {
            null
        }
        // Without a language chosen in the app, the app follows the device
        val tag = chosen?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
        return tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
    }

    override fun notifyUser(message: String) {
        CommonActivity.showToast(message, Toast.LENGTH_LONG)
    }

    override fun providersChanged() {
        MainActivity.afterPluginsLoadedEvent.invoke(false)
    }
}

/** Entry points of the add-on support for the rest of the app. */
object AndroidAddons {
    /** Safe to call any number of times, from any thread. */
    @Synchronized
    fun ensureInitialised(context: Context) {
        if (!AddonManager.isInitialised) {
            AddonManager.init(AndroidAddonPlatform(context))
        }
    }

    /**
     * Makes the saved Stremio, Nuvio and SkyStream sources usable. Reads only local files,
     * so it is fine to wait for it before the home page is shown.
     */
    suspend fun loadSaved(context: Context) {
        try {
            ensureInitialised(context)
            AddonManager.loadAll()
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** Looks for new versions of the saved sources, meant to run in the background. */
    suspend fun refreshInBackground(context: Context) {
        try {
            ensureInitialised(context)
            AddonManager.refreshStale()
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
