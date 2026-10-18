package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Closes the windows plugins show by themselves (announcements, donations, "join our Telegram").
 * A window counts as a plugin's when its buttons, its listeners or the dialog itself come from a
 * plugin's code; the windows of the app are never touched. Every new window is looked at for a
 * moment after it opens:
 *  - "ads": a plugin's window with words like Telegram, destek ol, bağış, kahve is closed
 *  - "all": every window a plugin opens by itself (not the ones right after a touch)
 * Switched off from outside with "ad_block".
 */
object AdBlock {
    const val PREF_ON = "fork_adblock"
    const val PREF_MODE = "fork_adblock_mode"
    const val PREF_NOTE = "fork_adblock_note"
    const val MODE_ADS = "ads"
    const val MODE_ALL = "all"
    private const val PREFS = "fork_adblock_log"

    /** words that mean "an announcement": looked for in the plugin's own windows */
    private val words = listOf(
        "telegram", "t.me/", "destek ol", "bağış", "bagis", "donate", "donation", "kanalımıza", "kanalimiza",
        "kanalına katıl", "kanalina katil", "kanala katıl", "kanalına katil", "discord", "patreon", "buy me a coffee",
        "ko-fi", "kofi", "kreosus", "kahve", "papara", "iban", "whatsapp", "instagram", "abone ol", "reklam",
        "sponsor", "ücretsiz kalacak", "ucretsiz kalacak", "hatırlatma", "hatirlatma", "güncel link", "guncel link",
        "strawpoll", "oy ver", "anket"
    )

    /** strong words: also enough for a window whose owner could not be found */
    private val strongWords = listOf("t.me/", "kreosus", "patreon", "buy me a coffee", "ko-fi", "papara", "strawpoll")

    private val handler = Handler(Looper.getMainLooper())
    private var running: Activity? = null

    @Volatile
    private var lastTouch = 0L

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.AD_BLOCK, context)

    fun on(context: Context): Boolean =
        available(context) && PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ON, false)

    /** MainActivity tells every touch: a window right after one was asked for. */
    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
    }

    // ---------------------------------------------------------------- what was blocked

    class Entry(val name: String, val title: String, val count: Int, val last: Long)

    fun log(context: Context): List<Entry> = try {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log2", "[]"))
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Entry(it.optString("n"), it.optString("t"), it.optInt("c"), it.optLong("l")) }
        }.sortedByDescending { it.last }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun remember(context: Context, name: String, title: String) {
        val list = log(context).toMutableList()
        val old = list.firstOrNull { it.name == name }
        list.remove(old)
        list.add(0, Entry(name, title.ifBlank { old?.title ?: "" }, (old?.count ?: 0) + 1, System.currentTimeMillis()))
        val a = JSONArray()
        list.take(30).forEach { a.put(JSONObject().put("n", it.name).put("t", it.title).put("c", it.count).put("l", it.last)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log2", a.toString()).apply()
    }

    fun clearLog(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("log2").remove("log").apply()
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
            handler.postDelayed(this, 400)
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

    private class Look {
        val texts = ArrayList<String>()
        var web = false
        var input = false
        var list = false
        var plugin: String? = null
    }

    private val appLoader: ClassLoader? = AdBlock::class.java.classLoader

    private fun pluginOf(o: Any?): String? {
        val loader = o?.javaClass?.classLoader ?: return null
        if (loader === appLoader || loader === Context::class.java.classLoader) return null
        return PluginManager.forkPluginNameOf(loader) ?: "?"
    }

    private fun field(o: Any, vararg names: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            for (n in names) {
                try {
                    return c.getDeclaredField(n).apply { isAccessible = true }.get(o)
                } catch (t: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }

    /** the click listener of a view (View.mListenerInfo.mOnClickListener) */
    private fun clickListener(v: View): Any? = try {
        field(v, "mListenerInfo")?.let { field(it, "mOnClickListener") }
    } catch (t: Throwable) {
        null
    }

    private fun look(view: View, out: Look) {
        if (out.texts.size > 80) return
        if (out.plugin == null && view.hasOnClickListeners()) pluginOf(clickListener(view))?.let { out.plugin = it }
        if (out.plugin == null) pluginOf(view)?.let { out.plugin = it } // a view class of the plugin
        when (view) {
            is WebView -> out.web = true
            is EditText -> out.input = true
            is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.texts.add(it) }
        }
        if (view is AbsListView || view.javaClass.name.endsWith("RecyclerView")) out.list = true
        if (view is ViewGroup) for (i in 0 until view.childCount) look(view.getChildAt(i), out)
    }

    /** the Dialog behind a window, when it can be reached */
    private fun dialogOf(root: View): Dialog? = try {
        (field(root, "mWindow") as? android.view.Window)?.callback as? Dialog
    } catch (t: Throwable) {
        null
    }

    /** the listeners an AlertDialog keeps for its buttons (they are not on the button views) */
    private fun alertListenerPlugin(dialog: Dialog?): String? {
        dialog ?: return null
        pluginOf(dialog)?.let { return it }
        val alert = field(dialog, "mAlert") ?: return null
        for (name in listOf("mButtonPositiveMessage", "mButtonNegativeMessage", "mButtonNeutralMessage")) {
            val msg = field(alert, name) as? android.os.Message ?: continue
            pluginOf(msg.obj)?.let { return it }
        }
        return null
    }

    private val firstSeen = java.util.WeakHashMap<View, Long>()

    private fun scan(activity: Activity) {
        val main = activity.window?.decorView
        val now = SystemClock.uptimeMillis()
        for (root in windows()) {
            if (root === main || !root.isShown) continue
            if (root.getTag(R.id.fork_adblock_own) == true || root.getTag(R.id.fork_adblock_seen) == true) continue
            val seen = firstSeen.getOrPut(root) { now }
            // a window is looked at for a while after it opens: its text and buttons may come a moment later
            if (now - seen > 3000) {
                root.setTag(R.id.fork_adblock_seen, true)
                continue
            }
            val l = Look()
            look(root, l)
            val dialog = dialogOf(root)
            if (l.plugin == null) l.plugin = alertListenerPlugin(dialog)
            if (l.texts.isEmpty() || l.web || l.input) continue

            val all = l.texts.joinToString("\n").lowercase(Locale("tr"))
            val mode = PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF_MODE, MODE_ADS)
            val isAd = words.any { all.contains(it) }
            val strong = strongWords.any { all.contains(it) }
            val block = when {
                l.plugin != null && mode == MODE_ALL -> !l.list && now - lastTouch > 1500 && seen - lastTouch > 1500
                l.plugin != null -> isAd
                else -> strong && !l.list // owner not found: only plain announcement words
            }
            if (!block) continue
            val title = l.texts.first().trim().take(60)
            val name = l.plugin?.takeIf { it != "?" } ?: title.ifBlank { activity.getString(R.string.adblock_plugin) }
            if (name in allowed(activity)) {
                root.setTag(R.id.fork_adblock_seen, true)
                continue
            }
            root.setTag(R.id.fork_adblock_seen, true)
            close(root, dialog)
            remember(activity, name, if (title != name) title else "")
            if (PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_NOTE, true)) {
                showToast(activity.getString(R.string.adblock_blocked, if (title.isNotBlank() && title != name) "$name · $title" else name), Toast.LENGTH_SHORT)
            }
        }
    }

    /** The dialog is dismissed; otherwise Back, and if it stays it is hidden and lets touches through. */
    private fun close(root: View, dialog: Dialog?) {
        try {
            if (dialog != null) {
                dialog.dismiss()
                return
            }
        } catch (t: Throwable) {
        }
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
