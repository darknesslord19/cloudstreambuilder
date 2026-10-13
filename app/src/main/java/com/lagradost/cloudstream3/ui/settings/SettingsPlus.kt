package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.Categories
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_plus, rootKey)
        val context = requireContext()

        // What is switched off from outside is not offered
        val categoriesOn = Categories.available(context)
        for (key in listOf(
            "fork_plus_open_categories", Categories.PREF_ICON, Categories.PREF_ANIM, Categories.PREF_RICH,
            Categories.PREF_BADGES, "fork_plus_remote_categories"
        )) {
            findPreference<Preference>(key)?.isVisible = categoriesOn
        }
        // The effects need the categories, and can be switched off from outside on their own
        val effectsAllowed = categoriesOn && ForkFeatures.isOn(RemoteRules.Feature.CATEGORY_EFFECTS, context)
        findPreference<Preference>(Categories.PREF_FX)?.isVisible = effectsAllowed
        findPreference<Preference>("fork_plus_category_fx_styles")?.isVisible = effectsAllowed
        findPreference<Preference>("fork_plus_category_fx_styles")?.setOnPreferenceClickListener {
            activity?.let { Categories.show(it, effects = true) }
            true
        }
        findPreference<Preference>(ForkFeatures.PREF_REPOLIST)?.isVisible =
            ForkFeatures.isOn(RemoteRules.Feature.REPOLIST, context)

        findPreference<Preference>("fork_plus_open_categories")?.setOnPreferenceClickListener {
            activity?.let { Categories.show(it) }
            true
        }

        // Whether the developer gives categories at the moment
        findPreference<Preference>("fork_plus_remote_categories")?.summary = try {
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
