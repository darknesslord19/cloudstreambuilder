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
import com.lagradost.cloudstream3.fork.TmdbPosters
import com.lagradost.cloudstream3.ui.clear
import com.lagradost.cloudstream3.ui.BasePreferenceFragmentCompat
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
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
        findPreference<Preference>("fork_plus_sheet_tmdb")?.isVisible = TmdbPosters.available(context)
        findPreference<Preference>("fork_plus_sheet_themes")?.isVisible =
            com.lagradost.cloudstream3.fork.ThemePack.available(context)
        findPreference<Preference>("fork_plus_sheet_looks")?.setOnPreferenceClickListener {
            activity?.let { showLooks(it) }
            true
        }
        findPreference<Preference>("fork_plus_sheet_themes")?.setOnPreferenceClickListener {
            activity?.let { com.lagradost.cloudstream3.fork.ThemePack.show(it) }
            true
        }
        findPreference<Preference>("fork_plus_sheet_tmdb")?.setOnPreferenceClickListener {
            activity?.let { showTmdb(it) }
            true
        }

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
        com.lagradost.cloudstream3.fork.ThemePack.nameOf(
            prefs.getString(getString(R.string.app_theme_key), null)
        )?.let { findPreference<Preference>("fork_plus_sheet_themes")?.summary = getString(R.string.tp_current, it) }
        findPreference<Preference>("fork_plus_sheet_tmdb")?.summary = when {
            !prefs.getBoolean(TmdbPosters.PREF_ON, true) -> getString(R.string.tmdb_off)
            TmdbPosters.key(context) == null -> getString(R.string.tmdb_no_key)
            else -> getString(R.string.tmdb_des)
        }
    }

    /** What was under Düzen: moving posters, font and app icon, with the themes. */
    private fun showLooks(activity: android.app.Activity) {
        val context = activity
        val items = ArrayList<PlusSheet.Item>()
        if (com.lagradost.cloudstream3.fork.ThemePack.available(context)) {
            items.add(PlusSheet.Item.Action(getString(R.string.tp_title), getString(R.string.tp_summary), R.drawable.format_paint_24px) {
                com.lagradost.cloudstream3.fork.ThemePack.show(activity)
            })
        }
        if (com.lagradost.cloudstream3.fork.PosterMotion.available(context)) {
            items.add(PlusSheet.Item.Action(getString(R.string.animated_posters), com.lagradost.cloudstream3.fork.PosterMotion.summary(context), R.drawable.animation_24px) {
                com.lagradost.cloudstream3.fork.PosterMotion.pick(activity) {
                    com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter.sharedPool.clear()
                    com.lagradost.cloudstream3.ui.home.ParentItemAdapter.sharedPool.clear()
                }
            })
        }
        val looks = com.lagradost.cloudstream3.fork.Looks
        if (ForkFeatures.isOn(RemoteRules.Feature.FONTS, context)) {
            items.add(PlusSheet.Item.Action(getString(R.string.app_font_settings), looks.currentFont(context).label, R.drawable.title_24px) {
                val current = looks.fonts.indexOf(looks.currentFont(activity))
                looks.pick(activity, getString(R.string.app_font_settings), looks.fontOptions(activity), current) { picked ->
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putString(looks.FONT_KEY, looks.fonts[picked].key).apply()
                    activity.recreate()
                }
            })
        }
        if (ForkFeatures.isOn(RemoteRules.Feature.ICONS, context)) {
            items.add(PlusSheet.Item.Action(getString(R.string.app_icon_settings), getString(looks.currentIcon(context).label), R.drawable.extension_24px) {
                val current = looks.icons.indexOf(looks.currentIcon(activity))
                looks.pick(activity, getString(R.string.app_icon_settings), looks.iconOptions(activity), current) { picked ->
                    if (picked == current) return@pick
                    val chosen = looks.icons[picked]
                    androidx.appcompat.app.AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                        .setTitle(R.string.app_icon_settings)
                        .setMessage(R.string.app_icon_restart_note)
                        .setPositiveButton(R.string.ok) { _, _ -> looks.applyIcon(activity, chosen) }
                        .setNegativeButton(R.string.cancel) { _, _ -> }
                        .show()
                }
            })
        }
        PlusSheet.show(activity, getString(R.string.plus_looks), getString(R.string.plus_looks_des), items)
    }

    private fun showTmdb(activity: android.app.Activity) {
        PlusSheet.show(
            activity, getString(R.string.tmdb_title), getString(R.string.tmdb_sheet_des), listOf(
                PlusSheet.Item.Switch(TmdbPosters.PREF_ON, getString(R.string.tmdb_main), getString(R.string.tmdb_main_des), icon = R.drawable.ic_fork_image_24),
                PlusSheet.Item.Header(getString(R.string.tmdb_where)),
                PlusSheet.Item.Switch(TmdbPosters.PREF_HOME, getString(R.string.tmdb_home), getString(R.string.tmdb_home_des), dependsOn = TmdbPosters.PREF_ON, icon = R.drawable.home_alt),
                PlusSheet.Item.Switch(TmdbPosters.PREF_CATS, getString(R.string.categories), getString(R.string.tmdb_cats_des), dependsOn = TmdbPosters.PREF_ON, icon = R.drawable.categories_24px),
                PlusSheet.Item.Header(getString(R.string.tmdb_settings)),
                PlusSheet.Item.Switch(TmdbPosters.PREF_HERO, getString(R.string.tmdb_hero), getString(R.string.tmdb_hero_des), dependsOn = TmdbPosters.PREF_ON, icon = R.drawable.ic_fork_image_24),
                PlusSheet.Item.Switch(TmdbPosters.PREF_BADGE, getString(R.string.tmdb_badge), getString(R.string.tmdb_badge_des), default = false, dependsOn = TmdbPosters.PREF_ON, icon = R.drawable.title_24px),
                PlusSheet.Item.Custom(dependsOn = TmdbPosters.PREF_ON) { keyRow(it) },
            )
        )
    }

    /** The key: locked when the developer gives one, otherwise the user saves their own. */
    private fun keyRow(activity: android.app.Activity): View {
        val ctx = activity
        val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
        val textColor = ctx.colorFromAttribute(R.attr.textColor)
        val grayColor = ctx.colorFromAttribute(R.attr.grayTextColor)
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.fork_quick_button)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
        }
        box.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.tmdb_key)
            textSize = 13f
            setTextColor(grayColor)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_fork_key_24, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        val admin = TmdbPosters.adminKey(ctx) != null
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val input = android.widget.EditText(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(grayColor)
            typeface = android.graphics.Typeface.MONOSPACE
            if (admin) {
                setText("••••••••••••••••••••••••")
                isEnabled = false
                alpha = 0.6f
            } else {
                hint = getString(R.string.tmdb_key_hint)
                setText(TmdbPosters.userKey(ctx) ?: "")
            }
        }
        val save = com.google.android.material.button.MaterialButton(ctx).apply {
            text = getString(if (admin) R.string.tmdb_locked else R.string.tmdb_save)
            isEnabled = !admin
            textSize = 13f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        row.addView(input)
        row.addView(save)
        box.addView(row)
        val state = android.widget.TextView(ctx).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        val okColor = android.graphics.Color.parseColor("#8FE3A7")
        val badColor = android.graphics.Color.parseColor("#FF9A9A")
        fun show(textRes: Int, color: Int) {
            state.text = getString(textRes)
            state.setTextColor(color)
        }
        when {
            admin -> show(R.string.tmdb_admin_key, okColor)
            TmdbPosters.userKey(ctx) != null -> show(R.string.tmdb_own_key, okColor)
            else -> show(R.string.tmdb_enter_key, grayColor)
        }
        box.addView(state)
        save.setOnClickListener {
            val key = input.text?.toString()?.trim().orEmpty()
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            if (key.isEmpty()) {
                prefs.edit().remove(TmdbPosters.PREF_USER_KEY).apply()
                show(R.string.tmdb_enter_key, grayColor)
                updateSummaries()
                return@setOnClickListener
            }
            save.isEnabled = false
            show(R.string.tmdb_testing, grayColor)
            ioSafe {
                val ok = TmdbPosters.test(key)
                main {
                    save.isEnabled = true
                    if (ok) {
                        prefs.edit().putString(TmdbPosters.PREF_USER_KEY, key).apply()
                        show(R.string.tmdb_saved, okColor)
                        updateSummaries()
                    } else {
                        show(R.string.tmdb_bad_key, badColor)
                    }
                }
            }
        }
        return box
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
