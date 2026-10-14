package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.AutoSubtitles
import com.lagradost.cloudstream3.fork.Categories
import com.lagradost.cloudstream3.fork.HomeTweaks
import com.lagradost.cloudstream3.fork.PlusSheet
import com.lagradost.cloudstream3.fork.ForkFeatures
import com.lagradost.cloudstream3.fork.RemoteRules
import com.lagradost.cloudstream3.ui.BasePreferenceFragmentCompat
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import org.json.JSONObject

/** Plus: the additions of this build in one place, each can be switched on and off. */
class SettingsPlus : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.plus)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    override fun onResume() {
        super.onResume()
        updateSummaries()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_plus, rootKey)
        val context = requireContext()

        // What is switched off from outside is not offered
        val categoriesOn = Categories.available(context)
        findPreference<Preference>("fork_plus_sheet_categories")?.isVisible = categoriesOn
        findPreference<Preference>("fork_plus_sheet_home")?.isVisible = HomeTweaks.available(context)
        findPreference<Preference>("fork_plus_sheet_subtitles")?.isVisible =
            ForkFeatures.isOn(RemoteRules.Feature.AUTO_SUBS, context)
        findPreference<Preference>("fork_plus_sheet_extensions")?.isVisible =
            ForkFeatures.isOn(RemoteRules.Feature.REPOLIST, context)

        findPreference<Preference>("fork_plus_sheet_home")?.setOnPreferenceClickListener {
            activity?.let { showHome(it) }
            true
        }
        findPreference<Preference>("fork_plus_sheet_categories")?.setOnPreferenceClickListener {
            activity?.let { showCategories(it) }
            true
        }
        findPreference<Preference>("fork_plus_sheet_subtitles")?.setOnPreferenceClickListener {
            activity?.let { showSubtitles(it) }
            true
        }
        findPreference<Preference>("fork_plus_sheet_extensions")?.setOnPreferenceClickListener {
            activity?.let { showExtensions(it) }
            true
        }
        updateSummaries()
    }

    /** "On" or "Off" in front of the line under a part with a main switch */
    private fun updateSummaries() {
        val context = context ?: return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        findPreference<Preference>("fork_plus_sheet_home")?.summary =
            if (prefs.getBoolean(HomeTweaks.PREF_ON, true)) getString(R.string.plus_home_des)
            else getString(R.string.plus_home_off)
    }

    private fun showHome(activity: android.app.Activity) {
        val items = ArrayList<PlusSheet.Item>()
        items.add(
            PlusSheet.Item.Switch(
                HomeTweaks.PREF_ON, getString(R.string.plus_home_main), getString(R.string.plus_home_main_des),
                icon = R.drawable.home_alt
            )
        )
        items.add(PlusSheet.Item.Header(getString(R.string.plus_home_parts)))
        HomeTweaks.Tweak.entries.forEach { tweak ->
            items.add(
                PlusSheet.Item.Switch(
                    tweak.key, getString(tweak.title), getString(tweak.summary), dependsOn = HomeTweaks.PREF_ON
                )
            )
        }
        PlusSheet.show(activity, getString(R.string.plus_home), getString(R.string.plus_home_sheet_des), items)
    }

    private fun showCategories(activity: androidx.fragment.app.FragmentActivity) {
        val context = activity
        val effectsAllowed = ForkFeatures.isOn(RemoteRules.Feature.CATEGORY_EFFECTS, context)
        val items = ArrayList<PlusSheet.Item>()
        items.add(PlusSheet.Item.Action(getString(R.string.plus_open_categories), getString(R.string.plus_open_categories_des), R.drawable.categories_24px) {
            Categories.show(activity)
        })
        items.add(PlusSheet.Item.Switch(Categories.PREF_ICON, getString(R.string.categories_icon_settings), getString(R.string.categories_icon_des), icon = R.drawable.categories_24px))
        items.add(PlusSheet.Item.Switch(Categories.PREF_ANIM, getString(R.string.categories_anim_settings), getString(R.string.categories_anim_des), icon = R.drawable.animation_24px))
        items.add(PlusSheet.Item.Switch(Categories.PREF_RICH, getString(R.string.categories_rich_settings), getString(R.string.categories_rich_des), default = false, dependsOn = Categories.PREF_ANIM, icon = R.drawable.plus_sparkle_24px))
        if (effectsAllowed) {
            items.add(PlusSheet.Item.Switch(Categories.PREF_FX, getString(R.string.categories_fx_settings), getString(R.string.categories_fx_des), icon = R.drawable.plus_sparkle_24px))
            items.add(PlusSheet.Item.Action(getString(R.string.categories_fx_styles), getString(R.string.categories_fx_styles_des), R.drawable.animation_24px, dependsOn = Categories.PREF_FX) {
                Categories.show(activity, effects = true)
            })
        }
        items.add(PlusSheet.Item.Switch(Categories.PREF_BADGES, getString(R.string.categories_badges_settings), getString(R.string.categories_badges_des), icon = R.drawable.title_24px))
        items.add(PlusSheet.Item.Info(getString(R.string.plus_remote_categories), remoteSummary(), R.drawable.mobile_arrow_down_24px))
        PlusSheet.show(activity, getString(R.string.categories), getString(R.string.plus_categories_des), items)
    }

    private fun showSubtitles(activity: android.app.Activity) {
        PlusSheet.show(
            activity, getString(R.string.player_subtitles_settings), getString(R.string.plus_subtitles_des), listOf(
                PlusSheet.Item.Switch(AutoSubtitles.PREF_ON, getString(R.string.autosub_settings), getString(R.string.autosub_des), icon = R.drawable.ic_baseline_subtitles_24),
                PlusSheet.Item.Choice(
                    AutoSubtitles.PREF_MODE, getString(R.string.autosub_mode),
                    listOf(getString(R.string.autosub_mode_before), getString(R.string.autosub_mode_live)),
                    listOf(AutoSubtitles.MODE_BEFORE, AutoSubtitles.MODE_LIVE),
                    AutoSubtitles.MODE_BEFORE, dependsOn = AutoSubtitles.PREF_ON, icon = R.drawable.ic_baseline_tune_24
                ),
            )
        )
    }

    private fun showExtensions(activity: android.app.Activity) {
        PlusSheet.show(
            activity, getString(R.string.extensions), getString(R.string.plus_extensions_des), listOf(
                PlusSheet.Item.Switch(ForkFeatures.PREF_REPOLIST, getString(R.string.repository_list), getString(R.string.repolist_switch_des), icon = R.drawable.extension_24px),
            )
        )
    }

    /** Whether the developer gives categories at the moment */
    private fun remoteSummary(): String {
        val context = context ?: return ""
        return try {
            val text = Categories.remoteDocument(context)
            if (text.isNullOrBlank()) {
                getString(R.string.plus_remote_none)
            } else {
                val doc = JSONObject(text)
                val enabled = doc.optJSONObject("settings")?.opt("enabled")?.toString()?.lowercase() != "false"
                val list = doc.opt("categories")
                val count = when (list) {
                    is org.json.JSONArray -> list.length()
                    is JSONObject -> list.length()
                    else -> 0
                }
                when {
                    !enabled -> getString(R.string.plus_remote_off)
                    count == 0 -> getString(R.string.plus_remote_none)
                    else -> getString(R.string.plus_remote_on, count)
                }
            }
        } catch (t: Throwable) {
            getString(R.string.plus_remote_none)
        }
    }
}
