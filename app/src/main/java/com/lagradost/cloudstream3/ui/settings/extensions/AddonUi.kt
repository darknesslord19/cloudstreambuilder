package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.addons.AddonEntry
import com.lagradost.cloudstream3.addons.AddonKind
import com.lagradost.cloudstream3.addons.AddonManager
import com.lagradost.cloudstream3.addons.AddonSource
import com.lagradost.cloudstream3.addons.android.AndroidAddons
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * The screens for Stremio, Nuvio and SkyStream sources. They are added through the same
 * "Add repository" dialog as CloudStream repositories and listed next to them, this object
 * holds what is different about them.
 */
object AddonUi {
    enum class Outcome {
        /** The address is not a Stremio, Nuvio or SkyStream source. */
        NOT_AN_ADDON,

        /** The source was added. */
        ADDED,

        /** It is such a source, but it was not added. The user has been told why. */
        NOT_ADDED,
    }

    /** How a source is shown in the list of repositories. */
    fun toRepositoryData(source: AddonSource): RepositoryData {
        val kind = source.addonKind?.label ?: source.kind
        return RepositoryData(source.iconUrl, "${AddonManager.displayNameOf(source)} ($kind)", source.url)
    }

    fun listedSources(): List<RepositoryData> {
        return try {
            AddonManager.getSources().map { toRepositoryData(it) }
        } catch (t: Throwable) {
            logError(t)
            emptyList()
        }
    }

    /**
     * Finds out if [input] is the address of a Stremio add-on, a Nuvio repository or a
     * SkyStream repository, and adds it if so. For repositories the user first picks which
     * scrapers or plugins to switch on. Must not be called on the main thread.
     */
    suspend fun tryAdd(activity: Activity?, input: String, customName: String?): Outcome {
        val act = activity ?: return Outcome.NOT_AN_ADDON
        AndroidAddons.ensureInitialised(act)
        val detected = try {
            AddonManager.detect(input)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logError(t)
            null
        } ?: return Outcome.NOT_AN_ADDON

        val enabled: List<String> = if (detected.entries.isEmpty()) {
            emptyList()
        } else {
            val preselected = detected.entries.indices.filter { detected.entries[it].enabledByDefault }
            val title = act.getString(R.string.addon_pick_entries, detected.name)
            val picked = pick(act, title, detected.entries, preselected) ?: return Outcome.NOT_ADDED
            if (picked.isEmpty()) {
                showToast(R.string.addon_nothing_selected, Toast.LENGTH_LONG)
                return Outcome.NOT_ADDED
            }
            picked.map { detected.entries[it].id }
        }

        return try {
            report(act, AddonManager.add(detected, customName, enabled), added = true)
            Outcome.ADDED
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logError(t)
            showToast(t.message ?: act.getString(R.string.error_invalid_data), Toast.LENGTH_LONG)
            Outcome.NOT_ADDED
        }
    }

    /**
     * SkyStream repositories can be shared as a short code, kept under "sky-CODE" at the
     * same link shortener CloudStream uses for its own codes.
     * @return the address behind the code, or null if there is none.
     */
    suspend fun resolveSkyStreamCode(input: String): String? {
        val code = input.trim()
        if (!code.matches(Regex("^[a-zA-Z0-9!_-]+$"))) return null
        return try {
            val response = app.get("https://cutt.ly/sky-$code", allowRedirects = false)
            val location = response.headers["Location"] ?: return null
            if (location.startsWith("https://cutt.ly/404") || location.removeSuffix("/") == "https://cutt.ly") null
            else location
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    /** Lets the user tick entries, returns the ticked positions or null if the dialog was closed. */
    private suspend fun pick(
        activity: Activity,
        title: String,
        entries: List<AddonEntry>,
        preselected: List<Int>,
    ): List<Int>? {
        val answer = CompletableDeferred<List<Int>?>()
        activity.runOnUiThread {
            try {
                activity.showMultiDialog(
                    entries.map { it.name },
                    preselected,
                    title,
                    // Runs for every way the dialog closes, after the callback below if "apply" was pressed
                    { answer.complete(null) },
                ) { selected -> answer.complete(selected) }
            } catch (t: Throwable) {
                logError(t)
                answer.complete(null)
            }
        }
        return answer.await()
    }

    private fun report(activity: Activity, result: AddonManager.Result, added: Boolean) {
        val lines = ArrayList<String>()
        lines.add(
            when {
                result.addedCatalog -> activity.getString(R.string.addon_added_with_catalog, result.source.name)
                added -> activity.getString(R.string.addon_added, result.source.name)
                else -> activity.getString(R.string.addon_saved, result.source.name)
            }
        )
        if (result.failed.isNotEmpty()) {
            lines.add(activity.getString(R.string.addon_downloads_failed, result.failed.joinToString(", ")))
        }
        showToast(lines.joinToString("\n"), Toast.LENGTH_LONG)
    }

    /**
     * What a tap on a source in the list does: Stremio add-ons show what they offer, for
     * Nuvio and SkyStream repositories the user can change what is switched on.
     * [onChanged] runs, off the main thread, after a change was saved.
     */
    fun open(activity: Activity?, source: AddonSource, onChanged: () -> Unit) {
        val act = activity ?: return
        AndroidAddons.ensureInitialised(act)
        when (source.addonKind) {
            AddonKind.STREMIO -> showStremioInfo(act, source)
            AddonKind.NUVIO -> act.showDialog(
                listOf(act.getString(R.string.addon_choose_entries), act.getString(R.string.addon_test_entries)),
                -1,
                AddonManager.displayNameOf(source),
                false,
                {},
            ) { picked ->
                if (picked == 1) testScrapers(act, source, onChanged) else chooseEntries(act, source, onChanged)
            }

            AddonKind.SKYSTREAM -> chooseEntries(act, source, onChanged)

            null -> {}
        }
    }

    /** Lets the user change which scrapers or plugins of a repository are switched on. */
    private fun chooseEntries(act: Activity, shown: AddonSource, onChanged: () -> Unit) {
        run {
            ioSafe {
                // What is switched on may have changed since the list was drawn
                val source = AddonManager.getSource(shown.url) ?: shown
                val entries = AddonManager.entriesOf(source)
                if (entries.isEmpty()) {
                    showToast(R.string.no_plugins_found_error, Toast.LENGTH_LONG)
                    return@ioSafe
                }
                val selected = entries.indices.filter { source.enabled.contains(entries[it].id) }
                val picked = pick(act, source.name, entries, selected) ?: return@ioSafe
                try {
                    val result = AddonManager.setEnabled(source.url, picked.map { entries[it].id })
                    if (result != null) report(act, result, added = false)
                } catch (t: Throwable) {
                    logError(t)
                    showToast(t.message ?: act.getString(R.string.error_invalid_data), Toast.LENGTH_LONG)
                }
                onChanged()
            }
        }
    }

    /**
     * What the settings icon next to a provider on the home page opens.
     * @param sourcePlugin the sourcePlugin of the provider.
     * @return null if the provider does not come from a Stremio, Nuvio or SkyStream source.
     */
    fun settingsActionFor(context: Context, sourcePlugin: String?): (() -> Unit)? {
        val activityOf = { (context as? Activity) ?: CommonActivity.activity }
        return try {
            if (AddonManager.isBuiltInCatalogProvider(sourcePlugin)) {
                return { openStreamSources(activityOf()) }
            }
            val source = AddonManager.sourceOfProvider(sourcePlugin) ?: return null
            return { open(activityOf(), source) {} }
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    /**
     * The built-in catalog has nothing to set itself, what matters to the user is where its
     * links come from: this opens the stream sources, asking which one if there are several.
     */
    private fun openStreamSources(activity: Activity?) {
        val act = activity ?: return
        val sources = AddonManager.streamSources()
        when (sources.size) {
            0 -> showToast(R.string.no_plugins_found_error, Toast.LENGTH_LONG)
            1 -> open(act, sources.first()) {}
            else -> act.showDialog(
                sources.map { "${AddonManager.displayNameOf(it)} (${it.addonKind?.label ?: it.kind})" },
                -1,
                AddonManager.builtInCatalogName(),
                false,
                {},
            ) { picked ->
                sources.getOrNull(picked)?.let { open(act, it) {} }
            }
        }
    }

    /** Tries every switched-on scraper of a Nuvio repository and shows which of them answer. */
    private fun testScrapers(activity: Activity, source: AddonSource, onChanged: () -> Unit) {
        showToast(R.string.addon_test_running, Toast.LENGTH_LONG)
        ioSafe {
            val checks = try {
                AddonManager.testScrapers(source.url)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError(t)
                emptyList()
            }
            if (checks.isEmpty()) {
                showToast(R.string.no_plugins_found_error, Toast.LENGTH_LONG)
                return@ioSafe
            }
            val working = checks.filter { it.links > 0 }
            val lines = checks.sortedWith(compareBy({ it.links == 0 }, { it.name.lowercase() })).map { check ->
                val seconds = String.format(java.util.Locale.ROOT, "%.1f", check.millis / 1000.0)
                when {
                    check.links > 0 -> activity.getString(R.string.addon_test_ok, check.name, check.links, seconds)
                    check.error != null -> activity.getString(R.string.addon_test_error, check.name, check.error.take(60))
                    else -> activity.getString(R.string.addon_test_none, check.name)
                }
            }
            val message = lines.joinToString("\n") + "\n\n" + activity.getString(R.string.addon_test_note)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                try {
                    val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                        .setTitle(activity.getString(R.string.addon_test_title, working.size, checks.size))
                        .setMessage(message)
                        .setPositiveButton(R.string.ok) { _, _ -> }
                    // Offered only if it leaves something switched on
                    if (working.isNotEmpty() && working.size < checks.size) {
                        builder.setNeutralButton(R.string.addon_test_disable_failed) { _, _ ->
                            ioSafe {
                                try {
                                    val result = AddonManager.setEnabled(source.url, working.map { it.id })
                                    if (result != null) report(activity, result, added = false)
                                } catch (t: Throwable) {
                                    logError(t)
                                }
                                onChanged()
                            }
                        }
                    }
                    builder.show().setDefaultFocus(DialogInterface.BUTTON_POSITIVE)
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        }
    }

    private fun showStremioInfo(activity: Activity, source: AddonSource) {
        val offers = AddonManager.capabilitiesOf(source)
        val labels = offers.map { capability ->
            activity.getString(
                when (capability) {
                    "catalog" -> R.string.addon_offers_catalog
                    "meta" -> R.string.addon_offers_details
                    "stream" -> R.string.addon_offers_streams
                    else -> R.string.addon_offers_subtitles
                }
            )
        }
        val message = StringBuilder()
        source.description?.takeIf { it.isNotBlank() }?.let { message.append(it.trim()).append("\n\n") }
        if (labels.isNotEmpty()) {
            message.append(activity.getString(R.string.addon_offers, labels.joinToString(", "))).append("\n\n")
        }
        if (!offers.contains("catalog")) {
            message.append(activity.getString(R.string.addon_no_catalog_note)).append("\n\n")
        }
        message.append(source.url)

        val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(AddonManager.displayNameOf(source))
            .setMessage(message.toString())
            .setPositiveButton(R.string.ok) { _, _ -> }
        AddonManager.configureUrlOf(source)?.let { configureUrl ->
            builder.setNeutralButton(R.string.addon_configure) { _, _ ->
                CloudStreamApp.openBrowser(configureUrl)
            }
        }
        builder.show().setDefaultFocus(DialogInterface.BUTTON_POSITIVE)
    }
}
