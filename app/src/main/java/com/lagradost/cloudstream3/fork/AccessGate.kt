package com.lagradost.cloudstream3.fork

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.webkit.JavascriptInterface
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fork.RemoteRules.Decision
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.AssetPage
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The optional access list of this build. While it is switched on at [BASE_URL], the app only
 * opens for devices that are in the list, each with its own name, password and end date.
 *
 * The document at [BASE_URL]:
 * {
 *   "settings": { "enabled": true, "telegram": "https://t.me/..." },
 *   "users": {
 *     "<device id>": { "name": "...", "password": "...", "expires": "2027-01-31", "active": true }
 *   }
 * }
 * "settings/enabled" switches the whole thing on and off, "active" a single user. The app asks
 * for "settings" and for its own entry only, never for the whole list.
 *
 * This is a door, not a vault: the source of the app is public, so it keeps honest users out,
 * not determined ones.
 */
object AccessGate {
    private const val BASE_URL =
        "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstreamkey"
    private const val DEFAULT_TELEGRAM = "https://t.me/darknes_lord"

    private const val PREFS = "fork_access"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GRANTED = "granted"
    private const val KEY_PASSWORD = "password"
    private const val KEY_USER = "user"
    private const val KEY_TELEGRAM = "telegram"
    private const val KEY_REASON = "reason"
    private const val KEY_DEVICE = "device"

    private const val RECHECK_AFTER_MS = 30L * 60L * 1000L
    private const val REASON_NO_CONNECTION = "NO_CONNECTION"

    @Volatile
    private var gate: AssetPage.Handle? = null

    @Volatile
    private var fallbackDialog: AlertDialog? = null

    @Volatile
    private var lastCheck = 0L
    private val checking = Mutex()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What identifies this installation in the list. It is the same after a reinstall. */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (t: Throwable) {
            null
        }
        if (!androidId.isNullOrBlank() && androidId.matches(Regex("^[0-9a-fA-F]{8,}$"))) {
            return androidId.lowercase(Locale.ROOT)
        }
        // A few devices have no usable id, they get one that lasts as long as the app data
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val made = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_DEVICE, made).apply()
        return made
    }

    /** What is known from the last check, without asking the server. */
    class State(
        val enabled: Boolean,
        val granted: Boolean,
        val name: String?,
        val expiresAt: Long?,
        val hasPassword: Boolean,
        val telegram: String,
        val reason: String?,
    )

    fun state(context: Context): State {
        val prefs = prefs(context)
        val user = RemoteRules.parse(prefs.getString(KEY_USER, null))
        val decision = RemoteRules.decide(user, prefs.getString(KEY_PASSWORD, null), System.currentTimeMillis())
        val info = when (decision) {
            is Decision.Granted -> decision.user
            is Decision.Denied -> decision.user
        }
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        return State(
            enabled = enabled,
            // An end date that passed while the app was closed counts at once
            granted = !enabled || (prefs.getBoolean(KEY_GRANTED, false) && decision is Decision.Granted),
            name = info?.name,
            expiresAt = info?.expiresAt,
            hasPassword = !prefs.getString(KEY_PASSWORD, null).isNullOrEmpty(),
            telegram = prefs.getString(KEY_TELEGRAM, null) ?: DEFAULT_TELEGRAM,
            reason = prefs.getString(KEY_REASON, null),
        )
    }

    /** If the settings should show the account row: only while the access list is switched on. */
    fun showsAccount(context: Context): Boolean = state(context).let { it.enabled && it.granted }

    fun remainingText(context: Context, expiresAt: Long?): String {
        if (expiresAt == null) return context.getString(R.string.access_unlimited)
        val now = System.currentTimeMillis()
        if (expiresAt <= now) return context.getString(R.string.access_expired)
        val left = RemoteRules.remaining(expiresAt, now)
        return when {
            left.years > 0 -> context.getString(R.string.access_left_years_days, left.years, left.days)
            left.days > 0 -> context.getString(R.string.access_left_days, left.days)
            left.hours > 0 -> context.getString(R.string.access_left_hours, left.hours)
            else -> context.getString(R.string.access_left_minutes, left.minutes.coerceAtLeast(1))
        }
    }

    private fun dateText(expiresAt: Long?): String? {
        if (expiresAt == null) return null
        return SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date(expiresAt))
    }

    /** One line for the settings: "Name • 54 years 94 days left". */
    fun accountSummary(context: Context): String {
        val state = state(context)
        return listOfNotNull(state.name, remainingText(context, state.expiresAt)).joinToString(" • ")
    }

    // ---------------------------------------------------------------- checking

    /** Call when the main screen starts: shows the door at once if it was closed last time. */
    fun onAppStart(activity: Activity) {
        try {
            val known = state(activity)
            if (known.enabled && !known.granted) showGate(activity)
        } catch (t: Throwable) {
            logError(t)
        }
        verify(activity, null, null)
    }

    /** Call when the main screen comes back: asks again after a while. */
    fun onResume(activity: Activity) {
        try {
            val known = state(activity)
            if (known.enabled && !known.granted && gate?.isOpen != true && fallbackDialog?.isShowing != true) {
                showGate(activity)
            }
        } catch (t: Throwable) {
            logError(t)
        }
        if (System.currentTimeMillis() - lastCheck > RECHECK_AFTER_MS) verify(activity, null, null)
    }

    private class Outcome(val open: Boolean, val reason: String?)

    /**
     * Asks the server and acts on the answer.
     * @param password what the user just typed, null to use the saved one.
     */
    private fun verify(activity: Activity, password: String?, onDone: ((Outcome) -> Unit)?) {
        ioSafe {
            // One check at a time, a login typed during the check at start waits for it
            val outcome = checking.withLock {
                try {
                    check(activity, password)
                } catch (t: Throwable) {
                    logError(t)
                    Outcome(state(activity).granted, null)
                }
            }
            lastCheck = System.currentTimeMillis()
            activity.runOnUiThread {
                try {
                    if (outcome.open) closeGate() else showGate(activity)
                    onDone?.invoke(outcome)
                } catch (t: Throwable) {
                    logError(t)
                }
            }
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

    private suspend fun check(context: Context, typedPassword: String?): Outcome {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val password = typedPassword?.trim()?.takeIf { it.isNotEmpty() } ?: prefs.getString(KEY_PASSWORD, null)

        val settingsText = fetch("$BASE_URL/settings.json")
        val userText = if (settingsText == null) null else {
            val settings = RemoteRules.accessSettings(RemoteRules.parse(settingsText))
            prefs.edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putString(KEY_TELEGRAM, settings.telegram)
                .apply()
            if (!settings.enabled) {
                prefs.edit().remove(KEY_REASON).apply()
                return Outcome(true, null)
            }
            fetch("$BASE_URL/users/" + URLEncoder.encode(deviceId(context), "UTF-8") + ".json")
        }

        if (userText == null) {
            // No connection: what was true at the last check stays true, apart from the end date
            if (!prefs.getBoolean(KEY_ENABLED, false)) return Outcome(true, null)
            val cached = RemoteRules.decide(RemoteRules.parse(prefs.getString(KEY_USER, null)), password, now)
            if (prefs.getBoolean(KEY_GRANTED, false) && cached is Decision.Granted) return Outcome(true, null)
            val reason = if (cached is Decision.Denied && cached.reason == RemoteRules.Denial.EXPIRED) {
                cached.reason.name
            } else {
                REASON_NO_CONNECTION
            }
            prefs.edit().putString(KEY_REASON, reason).apply()
            return Outcome(false, reason)
        }

        return when (val decision = RemoteRules.decide(RemoteRules.parse(userText), password, now)) {
            is Decision.Granted -> {
                prefs.edit()
                    .putBoolean(KEY_GRANTED, true)
                    .putString(KEY_USER, userText)
                    .putString(KEY_PASSWORD, password)
                    .remove(KEY_REASON)
                    .apply()
                Outcome(true, null)
            }

            is Decision.Denied -> {
                val editor = prefs.edit()
                    .putBoolean(KEY_GRANTED, false)
                    .putString(KEY_USER, userText)
                    .putString(KEY_REASON, decision.reason.name)
                if (decision.reason == RemoteRules.Denial.WRONG_PASSWORD) editor.remove(KEY_PASSWORD)
                editor.apply()
                Outcome(false, decision.reason.name)
            }
        }
    }

    // ---------------------------------------------------------------- the door

    /** What the pages login.html and account.html can call, as `Access.info()` and so on. */
    class Bridge(private val activity: Activity) {
        @JavascriptInterface
        fun info(): String {
            return try {
                val state = state(activity)
                JSONObject()
                    .put("deviceId", deviceId(activity))
                    .put("telegram", state.telegram)
                    .put("reason", state.reason ?: JSONObject.NULL)
                    .put("name", state.name ?: JSONObject.NULL)
                    .put("expires", dateText(state.expiresAt) ?: JSONObject.NULL)
                    .put("remaining", remainingText(activity, state.expiresAt))
                    .put("hasPassword", state.hasPassword)
                    .toString()
            } catch (t: Throwable) {
                logError(t)
                "{}"
            }
        }

        /** Tries [password], the page hears the result through window.onAccessResult(reason or null). */
        @JavascriptInterface
        fun login(password: String?) {
            activity.runOnUiThread {
                verify(activity, password ?: "") { outcome ->
                    val reason = if (outcome.open) "null" else JSONObject.quote(outcome.reason ?: REASON_NO_CONNECTION)
                    gate?.runScript("window.onAccessResult && window.onAccessResult($reason)")
                }
            }
        }

        @JavascriptInterface
        fun retry() = login(null)

        @JavascriptInterface
        fun openTelegram() = open(state(activity).telegram)

        /** Opens the chat with the device id already typed in. */
        @JavascriptInterface
        fun sendId() {
            val text = activity.getString(R.string.access_send_id_text, deviceId(activity))
            val base = state(activity).telegram
            val separator = if (base.contains('?')) "&" else "?"
            open(base + separator + "text=" + URLEncoder.encode(text, "UTF-8"))
        }

        /** Forgets the password on this device and goes back to the door. */
        @JavascriptInterface
        fun logout() {
            activity.runOnUiThread {
                prefs(activity).edit().remove(KEY_PASSWORD).putBoolean(KEY_GRANTED, false)
                    .putString(KEY_REASON, RemoteRules.Denial.PASSWORD_NEEDED.name).apply()
                accountPage?.close()
                showGate(activity)
            }
        }

        private fun open(url: String) {
            activity.runOnUiThread {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    logError(t)
                    showToast(R.string.error, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    @Volatile
    private var accountPage: AssetPage.Handle? = null

    /** The account page of the settings: name, device id, end date, time left. */
    fun showAccount(activity: Activity) {
        accountPage = AssetPage.show(activity, "account.html", bridges = mapOf("Access" to Bridge(activity)))
    }

    private fun closeGate() {
        gate?.close()
        gate = null
        try {
            fallbackDialog?.dismiss()
        } catch (t: Throwable) {
            logError(t)
        }
        fallbackDialog = null
    }

    private fun showGate(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (gate?.isOpen == true) {
            // Already there, it only needs to hear the news
            gate?.runScript("window.onAccessState && window.onAccessState()")
            return
        }
        if (fallbackDialog?.isShowing == true) return
        accountPage?.close()
        gate = AssetPage.show(
            activity, "login.html",
            cancelable = false,
            bridges = mapOf("Access" to Bridge(activity)),
            quiet = true,
        )
        if (gate == null) showFallback(activity)
    }

    /** The same door without a web page, for devices that cannot show one. */
    private fun showFallback(activity: Activity) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = activity.getString(R.string.access_password)
        }
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.access_title)
            .setMessage(activity.getString(R.string.access_device_id, deviceId(activity)))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.access_login, null)
            .setNeutralButton("Telegram", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                verify(activity, input.text?.toString() ?: "") { outcome ->
                    if (!outcome.open) showToast(R.string.access_denied, Toast.LENGTH_LONG)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                Bridge(activity).sendId()
            }
        }
        fallbackDialog = dialog
        try {
            dialog.show()
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
