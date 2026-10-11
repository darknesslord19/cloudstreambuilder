package com.lagradost.cloudstream3.addons

import com.lagradost.cloudstream3.addons.js.AddonJsEngine
import java.io.File

/**
 * The few things the add-on code needs from the app around it. Keeping them behind this
 * interface keeps the rest of the add-on code free of Android classes, so it can be run and
 * tested on a plain JVM.
 */
interface AddonPlatform {
    /** Reads a small persisted value, null if it was never set. */
    fun getValue(key: String): String?

    /** Persists a small value, null removes it. */
    fun setValue(key: String, value: String?)

    /** Directory where downloaded manifests and scripts are kept. */
    val storageDir: File

    /** Reads a bundled text file from assets/addons. */
    fun readAsset(name: String): String

    /** Runs the JavaScript of Nuvio and SkyStream plugins. */
    val jsEngine: AddonJsEngine

    /** Gives the app a chance to rewrite a download url, for example to a GitHub mirror. */
    fun rewriteUrl(url: String): String = url

    /**
     * The language new multi language providers should be filed under, picked so that the
     * provider is not hidden by the user's provider language filter.
     */
    fun defaultProviderLanguage(): String = "en"

    /** Two letter code of the language the app is shown in, "tr" for Turkish. */
    fun uiLanguage(): String = "en"

    /** Shows a short message to the user, may be called from any thread. */
    fun notifyUser(message: String) {}

    /** Called after providers were added or removed, so open screens can pick the change up. */
    fun providersChanged() {}
}
