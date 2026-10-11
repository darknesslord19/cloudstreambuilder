package com.lagradost.cloudstream3.ui.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.fork.Looks
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BasePreferenceFragmentCompat
import com.lagradost.cloudstream3.ui.clear
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.hideOn
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.toPx

class SettingsUI : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_ui)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_ui, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        (getPref(R.string.overscan_key)?.hideOn(PHONE or EMULATOR) as? SeekBarPreference)?.setOnPreferenceChangeListener { pref, newValue ->
            val padding = (newValue as? Int)?.toPx ?: return@setOnPreferenceChangeListener true
            (pref.context.getActivity() as? MainActivity)?.binding?.homeRoot?.setPadding(padding, padding, padding, padding)
            return@setOnPreferenceChangeListener true
        }

        getPref(R.string.bottom_title_key)?.setOnPreferenceChangeListener { _, _ ->
            HomeChildItemAdapter.sharedPool.clear()
            ParentItemAdapter.sharedPool.clear()
            SearchAdapter.sharedPool.clear()
            true
        }

        getPref(R.string.poster_size_key)?.setOnPreferenceChangeListener { _, newValue ->
            HomeChildItemAdapter.sharedPool.clear()
            ParentItemAdapter.sharedPool.clear()
            SearchAdapter.sharedPool.clear()
            context?.let { HomeChildItemAdapter.updatePosterSize(it, newValue as? Int) }
            true
        }

        getPref(R.string.poster_ui_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.poster_ui_options)
            val keys = resources.getStringArray(R.array.poster_ui_options_values)
            val prefValues = keys.map {
                settingsManager.getBoolean(it, true)
            }.mapIndexedNotNull { index, b ->
                if (b) {
                    index
                } else null
            }

            activity?.showMultiDialog(
                prefNames.toList(),
                prefValues,
                getString(R.string.poster_ui_settings),
                {}
            ) { list ->
                settingsManager.edit {
                    for ((i, key) in keys.withIndex()) {
                        putBoolean(key, list.contains(i))
                    }
                }
                SearchResultBuilder.updateCache(it.context)
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.app_layout_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.app_layout)
            val prefValues = resources.getIntArray(R.array.app_layout_values)

            val currentLayout =
                settingsManager.getInt(getString(R.string.app_layout_key), -1)

            activity?.showBottomDialog(
                items = prefNames.toList(),
                selectedIndex = prefValues.indexOf(currentLayout),
                name = getString(R.string.app_layout),
                showApply = true,
                dismissCallback = {},
                callback = {
                    try {
                        settingsManager.edit {
                            putInt(getString(R.string.app_layout_key), prefValues[it])
                        }
                        context?.updateTv()
                        activity?.recreate()
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            )
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.app_theme_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.themes_names).toMutableList()
            val prefValues = resources.getStringArray(R.array.themes_names_values).toMutableList()
            val removeIncompatible = { text: String ->
                val toRemove = prefValues
                    .mapIndexed { idx, s -> if (s.startsWith(text)) idx else null }
                    .filterNotNull()
                var offset = 0
                toRemove.forEach { idx ->
                    prefNames.removeAt(idx - offset)
                    prefValues.removeAt(idx - offset)
                    offset += 1
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { // remove monet on android 11 and less
                removeIncompatible("Monet")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Remove system on android 9 and less
                removeIncompatible("System")
            }

            val currentLayout =
                settingsManager.getString(getString(R.string.app_theme_key), prefValues.first())

            // Every theme is listed with a small picture of its colours
            val act = activity ?: return@setOnPreferenceClickListener true
            val accent = settingsManager.getString(getString(R.string.primary_color_key), "Normal")
            val options = prefNames.mapIndexed { index, name ->
                val value = prefValues[index]
                Looks.Option(
                    name,
                    palette = Looks.paletteOf(
                        act,
                        CommonActivity.themeStyleOf(act, value),
                        CommonActivity.accentStyleOf(value, accent),
                    )
                )
            }
            Looks.pick(act, getString(R.string.app_theme_settings), options, prefValues.indexOf(currentLayout)) {
                try {
                    settingsManager.edit {
                        putString(getString(R.string.app_theme_key), prefValues[it])
                    }
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        findPreference<Preference>(Looks.FONT_KEY)?.let { pref ->
            pref.summary = context?.let { Looks.currentFont(it).label }
            pref.setOnPreferenceClickListener {
                val act = activity ?: return@setOnPreferenceClickListener true
                val current = Looks.fonts.indexOf(Looks.currentFont(act))
                Looks.pick(act, getString(R.string.app_font_settings), Looks.fontOptions(act), current) { picked ->
                    try {
                        settingsManager.edit { putString(Looks.FONT_KEY, Looks.fonts[picked].key) }
                        activity?.recreate()
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
                return@setOnPreferenceClickListener true
            }
        }

        findPreference<Preference>(Looks.ICON_KEY)?.let { pref ->
            pref.summary = context?.let { getString(Looks.currentIcon(it).label) }
            pref.setOnPreferenceClickListener {
                val act = activity ?: return@setOnPreferenceClickListener true
                val current = Looks.icons.indexOf(Looks.currentIcon(act))
                Looks.pick(act, getString(R.string.app_icon_settings), Looks.iconOptions(act), current) { picked ->
                    val chosen = Looks.icons[picked]
                    if (picked == current) return@pick
                    // Changing the icon closes the app, so the user is told first
                    AlertDialog.Builder(act, R.style.AlertDialogCustom)
                        .setTitle(R.string.app_icon_settings)
                        .setMessage(R.string.app_icon_restart_note)
                        .setPositiveButton(R.string.ok) { _, _ -> Looks.applyIcon(act, chosen) }
                        .setNegativeButton(R.string.cancel) { _, _ -> }
                        .show()
                }
                return@setOnPreferenceClickListener true
            }
        }
        getPref(R.string.primary_color_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.themes_overlay_names).toMutableList()
            val prefValues =
                resources.getStringArray(R.array.themes_overlay_names_values).toMutableList()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { // remove monet on android 11 and less
                val toRemove = prefValues
                    .mapIndexed { idx, s -> if (s.startsWith("Monet")) idx else null }
                    .filterNotNull()
                var offset = 0
                toRemove.forEach { idx ->
                    prefNames.removeAt(idx - offset)
                    prefValues.removeAt(idx - offset)
                    offset += 1
                }
            }

            val currentLayout =
                settingsManager.getString(getString(R.string.primary_color_key), prefValues.first())

            activity?.showDialog(
                prefNames.toList(),
                prefValues.indexOf(currentLayout),
                getString(R.string.primary_color_settings),
                true,
                {}
            ) {
                try {
                    settingsManager.edit {
                        putString(getString(R.string.primary_color_key), prefValues[it])
                    }
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.pref_filter_search_quality_key)?.setOnPreferenceClickListener {
            val names = enumValues<SearchQuality>().sorted().map { it.name }
            val currentList = settingsManager.getStringSet(
                getString(R.string.pref_filter_search_quality_key),
                setOf()
            )?.map {
                it.toInt()
            } ?: listOf()

            activity?.showMultiDialog(
                names,
                currentList,
                getString(R.string.pref_filter_search_quality),
                {}
            ) { selectedList ->
                settingsManager.edit {
                    putStringSet(
                        getString(R.string.pref_filter_search_quality_key),
                        selectedList.map { it.toString() }.toMutableSet()
                    )
                }
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.confirm_exit_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.confirm_exit)
            val prefValues = resources.getIntArray(R.array.confirm_exit_values)
            val confirmExit = settingsManager.getInt(getString(R.string.confirm_exit_key), -1)

            activity?.showBottomDialog(
                items = prefNames.toList(),
                selectedIndex = prefValues.indexOf(confirmExit),
                name = getString(R.string.confirm_before_exiting_title),
                showApply = true,
                dismissCallback = {},
                callback = { selectedOption ->
                    settingsManager.edit {
                        putInt(getString(R.string.confirm_exit_key), prefValues[selectedOption])
                    }
                }
            )
            return@setOnPreferenceClickListener true
        }
    }
}