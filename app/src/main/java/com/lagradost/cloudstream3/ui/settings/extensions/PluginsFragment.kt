package com.lagradost.cloudstream3.ui.settings.extensions

import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentPluginsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

const val PLUGINS_BUNDLE_DATA = "data"
const val PLUGINS_BUNDLE_LOCAL = "isLocal"

class PluginsFragment : BaseFragment<FragmentPluginsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentPluginsBinding::inflate)
) {
    private lateinit var pluginViewModel: PluginsViewModel

    /** "Install all" is running */
    private var installing = false

    /** The line with how many plugins are installed, and the button to install the rest */
    private fun updateSummary(binding: FragmentPluginsBinding) {
        val box = binding.pluginsSummaryBox
        val all = pluginViewModel.allPlugins()
        box.root.isVisible = all.isNotEmpty()
        if (all.isEmpty() || installing) return
        val installed = all.count { it.isDownloaded }
        val missing = all.size - installed
        box.pluginsSummaryText.text = if (missing == 0) {
            getString(R.string.plugins_all_installed, all.size)
        } else {
            getString(R.string.plugins_installed_count, installed, all.size)
        }
        box.pluginsSummaryButton.isVisible = missing > 0
        box.pluginsSummaryDelete.isVisible = installed > 0
        box.pluginsSummaryProgress.isVisible = false
    }

    /** Asks what to install: everything missing, or only what the filters show. */
    private fun askInstall(binding: FragmentPluginsBinding, repositoryData: RepositoryData) {
        if (installing) return
        val ctx = context ?: return
        val missing = pluginViewModel.allPlugins().filter { !it.isDownloaded }.map { it.pluginWrapper }
        if (missing.isEmpty()) {
            showToast(R.string.plugins_nothing_missing, Toast.LENGTH_SHORT)
            return
        }
        val shown = pluginViewModel.shownPlugins().filter { !it.isDownloaded }.map { it.pluginWrapper }
        fun size(list: List<PluginWrapper>): String =
            formatShortFileSize(ctx, list.sumOf { it.plugin.fileSize ?: 0L })

        val choices = mutableListOf(missing)
        val labels = mutableListOf(getString(R.string.plugins_install_missing, missing.size, size(missing)))
        if (shown.isNotEmpty() && shown.size < missing.size) {
            choices.add(shown)
            labels.add(getString(R.string.plugins_install_shown, shown.size, size(shown)))
        }
        var picked = 0
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.plugins_install_all)
            .setSingleChoiceItems(labels.toTypedArray(), 0) { _, which -> picked = which }
            .setPositiveButton(R.string.plugins_install_confirm) { _, _ ->
                install(binding, repositoryData, choices[picked])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun install(binding: FragmentPluginsBinding, repositoryData: RepositoryData, list: List<PluginWrapper>) {
        installing = true
        val box = binding.pluginsSummaryBox
        box.pluginsSummaryButton.isVisible = false
        box.pluginsSummaryDelete.isVisible = false
        box.pluginsSummaryProgress.max = list.size
        box.pluginsSummaryProgress.setProgressCompat(0, false)
        box.pluginsSummaryProgress.isVisible = true
        box.pluginsSummaryText.text = getString(R.string.plugins_installing, 0, list.size)
        PluginsViewModel.installPlugins(
            activity, repositoryData, list, pluginViewModel,
            onProgress = { done, total ->
                if (isAdded) {
                    box.pluginsSummaryProgress.setProgressCompat(done, true)
                    box.pluginsSummaryText.text = getString(R.string.plugins_installing, done, total)
                }
            },
            onDone = { count ->
                installing = false
                if (isAdded) {
                    showToast(getString(R.string.plugins_installed_done, count), Toast.LENGTH_SHORT)
                    updateSummary(binding)
                }
            }
        )
    }

    /** Asks what to delete: every installed plugin of the repository, or only the ones shown. */
    private fun askDelete(binding: FragmentPluginsBinding, repositoryData: RepositoryData) {
        if (installing) return
        val ctx = context ?: return
        val installed = pluginViewModel.allPlugins().filter { it.isDownloaded }.map { it.pluginWrapper }
        if (installed.isEmpty()) {
            showToast(R.string.plugins_nothing_installed, Toast.LENGTH_SHORT)
            return
        }
        val shown = pluginViewModel.shownPlugins().filter { it.isDownloaded }.map { it.pluginWrapper }
        val choices = mutableListOf(installed)
        val labels = mutableListOf(getString(R.string.plugins_delete_installed, installed.size))
        if (shown.isNotEmpty() && shown.size < installed.size) {
            choices.add(shown)
            labels.add(getString(R.string.plugins_delete_shown, shown.size))
        }
        var picked = 0
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.plugins_delete_all)
            .setSingleChoiceItems(labels.toTypedArray(), 0) { _, which -> picked = which }
            .setPositiveButton(R.string.plugins_delete_confirm) { _, _ ->
                delete(binding, repositoryData, choices[picked])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(binding: FragmentPluginsBinding, repositoryData: RepositoryData, list: List<PluginWrapper>) {
        installing = true
        val box = binding.pluginsSummaryBox
        box.pluginsSummaryButton.isVisible = false
        box.pluginsSummaryDelete.isVisible = false
        box.pluginsSummaryProgress.max = list.size
        box.pluginsSummaryProgress.setProgressCompat(0, false)
        box.pluginsSummaryProgress.isVisible = true
        box.pluginsSummaryText.text = getString(R.string.plugins_deleting, 0, list.size)
        PluginsViewModel.deletePlugins(
            activity, repositoryData, list, pluginViewModel,
            onProgress = { done, total ->
                if (isAdded) {
                    box.pluginsSummaryProgress.setProgressCompat(done, true)
                    box.pluginsSummaryText.text = getString(R.string.plugins_deleting, done, total)
                }
            },
            onDone = { count ->
                installing = false
                if (isAdded) {
                    showToast(getString(R.string.plugins_deleted_done, count), Toast.LENGTH_SHORT)
                    updateSummary(binding)
                }
            }
        )
    }

    override fun onDestroyView() {
        pluginViewModel.clear() // clear for the next observe
        super.onDestroyView()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    override fun onBindingCreated(binding: FragmentPluginsBinding) {
        pluginViewModel = ViewModelProvider(this)[PluginsViewModel::class.java]

        // Since the ViewModel is getting reused the tvTypes must be cleared between uses
        pluginViewModel.tvTypes.clear()
        pluginViewModel.selectedLanguages = listOf()
        pluginViewModel.clear()

        // Filter by language set on preferred media
        activity?.let {
            val providerLangs = it.getApiProviderLangSettings().toList()
            if (!providerLangs.contains(AllLanguagesName)) {
                pluginViewModel.selectedLanguages = mutableListOf("none") + providerLangs
            }
        }

        val repositoryData = arguments?.getString(PLUGINS_BUNDLE_DATA)?.let { data ->
            tryParseJson<RepositoryData>(data)
        }
        val isLocal = arguments?.getBoolean(PLUGINS_BUNDLE_LOCAL) == true
        // download all extensions button
        val downloadAllButton = binding.settingsToolbar.menu?.findItem(R.id.download_all)

        if (repositoryData == null) {
            dispatchBackPressed()
            return
        }

        setToolBarScrollFlags()
        setUpToolbar(repositoryData.name)
        binding.settingsToolbar.apply {
            setOnMenuItemClickListener { menuItem ->
                when (menuItem?.itemId) {
                    R.id.download_all -> askInstall(binding, repositoryData)

                    R.id.lang_filter -> {
                        val languagesTagName = pluginViewModel.pluginLanguages
                            .map { langTag ->
                                Pair(
                                    langTag,
                                    getNameNextToFlagEmoji(langTag) ?: langTag
                                )
                            }
                            .sortedBy {
                                it.second.substringAfter("\u00a0").lowercase()
                            } // name ignoring flag emoji
                            .toMutableList()

                        // Move "none" to 1st position as it's special code to indicate unknown/missing language
                        if (languagesTagName.remove(Pair("none", "none"))) {
                            languagesTagName.add(0, Pair("none", getString(R.string.no_data)))
                        }

                        val currentIndexList = pluginViewModel.selectedLanguages.map { langTag ->
                            languagesTagName.indexOfFirst { lang -> lang.first == langTag }
                        }

                        activity?.showMultiDialog(
                            languagesTagName.map { it.second },
                            currentIndexList,
                            getString(R.string.provider_lang_settings),
                            {}
                        ) { selectedList ->
                            pluginViewModel.selectedLanguages =
                                selectedList.map { languagesTagName[it].first }
                            pluginViewModel.updateFilteredPlugins()
                        }
                    }

                    else -> {}
                }
                return@setOnMenuItemClickListener true
            }

            val searchView =
                menu?.findItem(R.id.search_button)?.actionView as? SearchView

            // Don't go back if active query
            setNavigationOnClickListener {
                if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }
//        searchView?.onActionViewCollapsed = {
//            pluginViewModel.search(null)
//        }

        // Because onActionViewCollapsed doesn't wanna work we need this workaround :(

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter =
                PluginAdapter {
                    pluginViewModel.handlePluginAction(activity, listOf(repositoryData), it, isLocal)
                }
        }

        if (isLayout(TV or EMULATOR)) {
            // Scrolling down does not reveal the whole RecyclerView on TV, add to bypass that.
            binding.pluginRecyclerView.setPadding(0, 0, 0, 200.toPx)
        }

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            if (!isLocal) updateSummary(binding)
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        if (isLocal) {
            // No download button and no categories on local
            downloadAllButton?.isVisible = false
            binding.settingsToolbar.menu?.findItem(R.id.lang_filter)?.isVisible = false
            pluginViewModel.updatePluginListLocal()

            binding.tvtypesChipsScroll.root.isVisible = false
            binding.pluginsSummaryBox.root.isVisible = false
        } else {
            pluginViewModel.updatePluginList(context, listOf(repositoryData))
            binding.tvtypesChipsScroll.root.isVisible = true
            // Install all: in the bar on top and on the line above the list
            downloadAllButton?.isVisible = true
            context?.let { ctx -> downloadAllButton?.icon?.mutate()?.setTint(ctx.colorFromAttribute(R.attr.colorPrimary)) }
            binding.pluginsSummaryBox.root.isVisible = false
            binding.pluginsSummaryBox.pluginsSummaryButton.setOnClickListener { askInstall(binding, repositoryData) }
            binding.pluginsSummaryBox.pluginsSummaryDelete.setOnClickListener { askDelete(binding, repositoryData) }

            bindChips(
                binding.tvtypesChipsScroll.tvtypesChips,
                emptyList(),
                TvType.entries.toList(),
                callback = { list ->
                    pluginViewModel.tvTypes.clear()
                    pluginViewModel.tvTypes.addAll(list.map { it.name })
                    pluginViewModel.updateFilteredPlugins()
                },
                nextFocusDown = R.id.plugins_summary_button,
                nextFocusUp = null,
            )
        }
    }

    companion object {
        fun newInstance(repositoryData: RepositoryData): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_DATA, repositoryData.toJson())
                putBoolean(PLUGINS_BUNDLE_LOCAL, false)
            }
        }
         fun newLocalInstance(name: String): Bundle {
            return Bundle().apply {
                putString(PLUGINS_BUNDLE_DATA, RepositoryData("", name, "").toJson())
                putBoolean(PLUGINS_BUNDLE_LOCAL, true)
            }
        }
    }
}