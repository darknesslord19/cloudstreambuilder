package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe

/**
 * A message from the developer, shown when the app starts. It is read from [URL]:
 *
 * { "show": true, "id": 1, "title": "...", "message": "...",
 *   "button_text": "Telegram", "button_url": "https://t.me/...", "once": true }
 *
 * A message is shown once, give it a new "id" to show something again. With "once": false it
 * is shown at every start for as long as "show" is true.
 */
object Announcements {
    private const val URL =
        "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstreamduyuru.json"
    private const val PREFS = "fork_announcements"
    private const val KEY_SEEN = "seen"

    @Volatile
    private var shownThisStart = false

    fun check(activity: Activity) {
        if (shownThisStart) return
        ioSafe {
            val announcement = try {
                RemoteRules.announcement(RemoteRules.parse(app.get(URL).text))
            } catch (t: Throwable) {
                null
            } ?: return@ioSafe

            val prefs = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (announcement.once && prefs.getString(KEY_SEEN, null) == announcement.id) return@ioSafe

            activity.runOnUiThread {
                if (shownThisStart || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                // Not on top of the login page, it is shown at the next start instead
                if (!AccessGate.state(activity).granted) return@runOnUiThread
                try {
                    val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                        .setMessage(announcement.message)
                        .setPositiveButton(R.string.ok) { _, _ -> }
                    announcement.title?.let { builder.setTitle(it) }
                    val url = announcement.buttonUrl
                    if (url != null) {
                        builder.setNeutralButton(announcement.buttonText ?: activity.getString(R.string.announcement_open)) { _, _ ->
                            try {
                                activity.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (t: Throwable) {
                                logError(t)
                                showToast(R.string.error, Toast.LENGTH_SHORT)
                            }
                        }
                    }
                    builder.show()
                    shownThisStart = true
                    prefs.edit().putString(KEY_SEEN, announcement.id).apply()
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        }
    }
}
