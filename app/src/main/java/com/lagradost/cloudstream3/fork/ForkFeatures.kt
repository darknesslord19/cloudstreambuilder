package com.lagradost.cloudstream3.fork

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import java.net.URLEncoder

/**
 * Which additions of this build are switched on. Read from [GENERAL_URL] for everyone, and
 * from the "features" of this device in the access list for single users:
 *
 *   Cloudstreamplus.json: { "stremio": true, "nuvio": true, "skystream": true, "animated_posters": true,
 *                           "themes": true, "fonts": true, "icons": true }
 *   Cloudstreamkey.json, users/<device id>: { ..., "features": { "nuvio": false } }
 *
 * What was read last is kept, so without a connection nothing changes.
 */
object ForkFeatures {
    private const val GENERAL_URL = "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstreamplus.json"
    private const val USERS_URL = "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstreamkey/users/"

    private const val PREFS = "fork_features"
    private const val KEY_GENERAL = "general"
    private const val KEY_USER = "user"

    @Volatile
    private var cached: Map<Feature, Boolean>? = null

    private fun context(context: Context?): Context? = context ?: CloudStreamApp.context

    private fun compute(context: Context): Map<Feature, Boolean> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RemoteRules.features(
            RemoteRules.parse(prefs.getString(KEY_GENERAL, null)),
            RemoteRules.parse(prefs.getString(KEY_USER, null)),
        )
    }

    /** The repository list button of the "Add repository" dialog, also switched in the layout settings */
    const val PREF_REPOLIST = "fork_repolist"

    fun repoListShown(context: Context?): Boolean {
        val ctx = context(context) ?: return true
        if (!isOn(Feature.REPOLIST, ctx)) return false
        return try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_REPOLIST, true)
        } catch (t: Throwable) {
            true
        }
    }

    /** The general document (Cloudstreamplus.json) as it was read last. */
    fun general(context: Context?): com.fasterxml.jackson.databind.JsonNode? {
        val ctx = context(context) ?: return null
        return try {
            RemoteRules.parse(
                ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GENERAL, null)
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** If [feature] is switched on. Everything is on until the documents say otherwise. */
    fun isOn(feature: Feature, context: Context? = null): Boolean {
        cached?.let { return it[feature] != false }
        val ctx = context(context) ?: return true
        return try {
            val computed = compute(ctx)
            cached = computed
            computed[feature] != false
        } catch (t: Throwable) {
            true
        }
    }

    private suspend fun fetch(url: String): String? {
        return try {
            val response = app.get(url)
            if (response.code in 200..299) response.text else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Reads both documents again.
     * @return the features that changed, empty if nothing changed or nothing could be read.
     */
    suspend fun refresh(context: Context): Set<Feature> {
        val general = fetch(GENERAL_URL)
        val user = fetch(USERS_URL + URLEncoder.encode(AccessGate.deviceId(context), "UTF-8") + ".json")
        if (general == null && user == null) return emptySet()

        val before = Feature.values().associateWith { isOn(it, context) }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (general != null) editor.putString(KEY_GENERAL, general)
        if (user != null) editor.putString(KEY_USER, user)
        editor.commit()
        val after = compute(context)
        cached = after
        return Feature.values().filter { before[it] != after[it] }.toSet()
    }
}
