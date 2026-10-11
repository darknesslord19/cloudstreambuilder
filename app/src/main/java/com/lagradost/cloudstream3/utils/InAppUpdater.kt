package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    private const val GITHUB_USER_NAME = "recloudstream"
    private const val GITHUB_REPO = "cloudstream"

    /**
     * Where this build looks for its updates. The address answers with a JSON object:
     *
     * {
     *   "version_code": 3,                    build number of the newest version (required)
     *   "version_name": "4.8.0",              shown to the user
     *   "title": "...", "message": "...",     texts of the dialog
     *   "changelog": ["...", "..."],          a list or a single text
     *   "update_url": "https://.../app.apk",  an .apk is installed by the app, any other
     *                                         address is opened in the browser
     *   "force": false,                       true: the dialog cannot be dismissed
     *   "show": true                          false: nothing is shown
     * }
     *
     * "versionCode", "versionName", "downloadUrl" and "pageUrl" are understood as well.
     * An update is offered when version_code is higher than [BuildConfig.FORK_BUILD].
     */
    private const val UPDATE_INFO_URL =
        "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstream.json"

    private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"
    private const val LOG_TAG = "InAppUpdater"

    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
        /** Title of the dialog, null for the usual "old -> new" title */
        @JsonProperty("title") @SerialName("title") val title: String? = null,
        /** If the dialog can not be dismissed */
        @JsonProperty("force") @SerialName("force") val force: Boolean = false,
        /** If [updateURL] is an apk the app can install, otherwise it is a page to open */
        @JsonProperty("directApk") @SerialName("directApk") val directApk: Boolean = true,
    )

    @Suppress("UNUSED_PARAMETER")
    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                // This build has its own update address, see [UPDATE_INFO_URL]
                else -> getForkUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    private suspend fun getForkUpdate(): Update {
        val none = Update(false, null, null, null, null)
        val json = try {
            val answer = mapper.readTree(app.get(UPDATE_INFO_URL).text)
            // The object may have been saved as a text, that is with quotes around it
            if (answer != null && answer.isTextual && answer.asText().trim().startsWith("{")) {
                mapper.readTree(answer.asText())
            } else {
                answer
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            null
        }
        // Anything but an object means that no update was published
        if (json == null || !json.isObject) return none

        fun text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            json.get(name)?.takeIf { it.isValueNode && !it.isNull }?.asText()?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        fun flag(name: String, default: Boolean): Boolean {
            val node = json.get(name) ?: return default
            return when {
                node.isBoolean -> node.asBoolean()
                node.isNumber -> node.asInt() != 0
                node.isTextual -> node.asText().trim().equals("true", ignoreCase = true)
                else -> default
            }
        }

        if (!flag("show", true)) return none

        val newBuild = text("version_code", "versionCode", "build")?.toDoubleOrNull()?.toInt()
            ?: return none
        Log.d(LOG_TAG, "Published build: $newBuild, this build: ${BuildConfig.FORK_BUILD}")
        if (newBuild <= BuildConfig.FORK_BUILD) return none

        fun isWebAddress(url: String) = url.startsWith("https://") || url.startsWith("http://")
        fun isApk(url: String) =
            url.substringBefore('#').substringBefore('?').endsWith(".apk", ignoreCase = true)

        val download = text("downloadUrl", "download_url", "apkUrl", "apk_url")?.takeIf { isWebAddress(it) }
        val page = text("update_url", "updateUrl", "pageUrl", "page_url", "url")?.takeIf { isWebAddress(it) }
        val url = download ?: page ?: return none

        val changelogNode = json.get("changelog")
        val changelog = when {
            changelogNode == null || changelogNode.isNull -> null
            changelogNode.isArray -> changelogNode.mapNotNull { line ->
                line.asText()?.trim()?.takeIf { it.isNotEmpty() }
            }.joinToString("\n")
            else -> changelogNode.asText()?.trim()
        }?.takeIf { it.isNotEmpty() }

        return Update(
            shouldUpdate = true,
            updateURL = url,
            updateVersion = text("version_name", "versionName") ?: newBuild.toString(),
            changelog = listOfNotNull(text("message"), changelog).joinToString("\n\n"),
            updateNodeId = "fork-$newBuild",
            title = text("title"),
            force = flag("force", false),
            directApk = download != null || isApk(url),
        )
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(url, headers = headers).text
        ).toList()

        val versionRegex = Regex("""(.*?((\d+)\.(\d+)\.(\d+))\.apk)""")
        val versionRegexLocal = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*)""")
        val foundList = response.filter { rel ->
            !rel.prerelease
        }.sortedWith(compareBy { release ->
            release.assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" }?.name?.let { it1 ->
                versionRegex.find(it1)?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }
        }).toList()

        val found = foundList.lastOrNull()
        val foundAsset = found?.assets?.getOrNull(0)
        val foundVersion = foundAsset?.name?.let { versionRegex.find(it) }

        if (foundVersion == null) {
            return Update(false, null, null, null, null)
        }

        val currentVersion = packageName?.let {
            packageManager.getPackageInfo(it, 0)
        }

        val shouldUpdate = if (foundAsset.browserDownloadUrl.isBlank()) {
            false
        } else {
            currentVersion?.versionName?.let { versionName ->
                versionRegexLocal.find(versionName)?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }?.compareTo(
                foundVersion.groupValues.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                })!! < 0
        }

        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            foundVersion.groupValues[2],
            found.body,
            found.nodeId
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val tagUrl =
            "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(releaseUrl, headers = headers).text
        ).toList()

        val found = response.lastOrNull { rel ->
            rel.prerelease || rel.tagName == "pre-release"
        }

        val foundAsset = found?.assets?.filter { it ->
            it.contentType == "application/vnd.android.package-archive"
        }?.getOrNull(0)

        if (foundAsset == null) {
            return Update(false, null, null, null, null)
        }

        val tagResponse = parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        val updateCommitHash = tagResponse.githubObject.sha.trim().take(7)
        Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash")

        return Update(
            currentCommitHash() != updateCommitHash,
            foundAsset.browserDownloadUrl,
            updateCommitHash,
            found.body,
            found.nodeId
        )
    }

    private val updateLock = Mutex()

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            val appUpdateName = "CloudStream"
            val appUpdateSuffix = "apk"

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix")
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                sink.writeAll(app.get(url).body.source())
                sink.close()
                openApk(this, Uri.fromFile(downloadedFile))
            }

            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually. A forced update can not be skipped.
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate && !update.force) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    update.title?.let { title -> "$title (${update.updateVersion})" }
                        ?: getString(R.string.new_update_format).format(
                            currentVersion?.versionName, update.updateVersion
                        )
                )
                builder.setCancelable(!update.force)

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        if (!update.directApk) {
                            // Not a file the app can install, the page it is offered on is opened
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(update.updateURL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                logError(e)
                                showToast(R.string.error, Toast.LENGTH_SHORT)
                            }
                            return@setPositiveButton
                        }

                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 1
                        )

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val intent = PackageInstallerService.Companion.getIntent(
                                    this@runAutoUpdate, update.updateURL
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!update.force) {
                        setNegativeButton(R.string.cancel) { _, _ -> }
                    }

                    if (checkAutoUpdate && !update.force) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
