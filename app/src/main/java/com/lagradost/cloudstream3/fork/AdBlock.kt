package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Closes the windows plugins show by themselves (announcements, donations, "join our Telegram").
 * The windows of the app are left alone. Every window that opens is looked at once:
 *  - "ads": windows with words like Telegram, destek ol, bağış are closed
 *  - "all": also every window whose title is the name of a plugin
 * Switched off from outside with "ad_block".
 */
object AdBlock {
    const val PREF_ON = "fork_adblock"
    const val PREF_MODE = "fork_adblock_mode"
    const val PREF_NOTE = "fork_adblock_note"
    const val MODE_ADS = "ads"
    const val MODE_ALL = "all"
    private const val PREFS = "fork_adblock_log"

    private val words = listOf(
        "telegram", "t.me/", "destek ol", "bağış", "bagis", "donate", "donation", "kanalımıza", "kanalimiza",
        "kanalına katıl", "kanalina katil", "kanala katıl", "discord", "patreon", "buy me a coffee", "ko-fi",
        "kofi", "reklam", "sponsor", "papara", "iban", "whatsapp", "instagram", "abone ol", "kahve ısmarla"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var running: Activity? = null

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.AD_BLOCK, context)

    fun on(context: Context): Boolean =
        available(context) && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ON, false)

    // ---------------------------------------------------------------- what was blocked

    class Entry(val name: String, val count: Int, val last: Long)

    fun log(context: Context): List<Entry> = try {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Entry(it.optString("n"), it.optInt("c"), it.optLong("t")) } }
            .sortedByDescending { it.last }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun remember(context: Context, name: String) {
        val list = log(context).toMutableList()
        val old = list.firstOrNull { it.name == name }
        list.remove(old)
        list.add(0, Entry(name, (old?.count ?: 0) + 1, System.currentTimeMillis()))
        val a = JSONArray()
        list.take(30).forEach { a.put(JSONObject().put("n", it.name).put("c", it.count).put("t", it.last)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log", a.toString()).apply()
    }

    fun allowed(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("allowed", emptySet()) ?: emptySet()

    fun setAllowed(context: Context, name: String, allow: Boolean) {
        val set = allowed(context).toMutableSet()
        if (allow) set.add(name) else set.remove(name)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("allowed", set).apply()
    }

    // ---------------------------------------------------------------- watching the windows

    private val tick = object : Runnable {
        override fun run() {
            val activity = running ?: return
            try {
                if (on(activity)) scan(activity)
            } catch (t: Throwable) {
                // nothing
            }
            handler.postDelayed(this, 600)
        }
    }

    fun start(activity: Activity) {
        running = activity
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = null
        handler.removeCallbacks(tick)
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                android.view.inspector.WindowInspector.getGlobalWindowViews()
            } catch (t: Throwable) {
                emptyList()
            }
        }
        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val field = global.getDeclaredField("mViews").apply { isAccessible = true }
            (field.get(instance) as? List<View>)?.toList() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun texts(view: View, out: MutableList<String>) {
        if (out.size > 60) return
        when (view) {
            is WebView -> out.add("\u0000web")
            is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            is ViewGroup -> for (i in 0 until view.childCount) texts(view.getChildAt(i), out)
        }
    }

    private fun scan(activity: Activity) {
        val main = activity.window?.decorView
        for (root in windows()) {
            if (root === main || !root.isShown) continue
            if (root.getTag(R.id.fork_adblock_seen) == true || root.getTag(R.id.fork_adblock_own) == true) continue
            root.setTag(R.id.fork_adblock_seen, true)
            val found = ArrayList<String>()
            texts(root, found)
            if (found.isEmpty() || found.contains("\u0000web")) continue
            val all = found.joinToString("\n").lowercase(Locale("tr"))
            // only message windows (the plugins' announcements are such); menus and pages of the app are not
            val titleView = root.findViewById<TextView>(androidx.appcompat.R.id.alertTitle) ?: root.findViewById(android.R.id.title)
            val messageView = root.findViewById<View>(android.R.id.message)
            if (titleView == null && messageView == null) continue
            val title = titleView?.text?.toString()?.trim()
            val plugin = pluginName(title, found)
            val isAd = words.any { all.contains(it) }
            val mode = PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF_MODE, MODE_ADS)
            val block = isAd || (mode == MODE_ALL && plugin != null)
            if (!block) continue
            val name = plugin ?: title?.ifEmpty { null } ?: activity.getString(R.string.adblock_plugin)
            if (name in allowed(activity)) continue
            close(root)
            remember(activity, name)
            if (PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_NOTE, true)) {
                showToast(activity.getString(R.string.adblock_blocked, name), Toast.LENGTH_SHORT)
            }
        }
    }

    private fun pluginName(title: String?, texts: List<String>): String? {
        val names = try {
            APIHolder.allProviders.withLock { APIHolder.allProviders.map { it.name } }
        } catch (t: Throwable) {
            emptyList()
        }
        val first = (title ?: texts.firstOrNull() ?: return null).lowercase()
        return names.firstOrNull { it.length > 2 && first.contains(it.lowercase()) }
    }

    /** Back first (a window that can be closed closes), otherwise it is hidden and lets touches through. */
    private fun close(root: View) {
        try {
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        } catch (t: Throwable) {
        }
        root.postDelayed({
            try {
                if (!root.isAttachedToWindow) return@postDelayed
                root.visibility = View.GONE
                val lp = root.layoutParams as? WindowManager.LayoutParams ?: return@postDelayed
                lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                lp.dimAmount = 0f
                (root.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.updateViewLayout(root, lp)
            } catch (t: Throwable) {
            }
        }, 200)
    }
}
