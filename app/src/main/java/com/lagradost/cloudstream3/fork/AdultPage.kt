package com.lagradost.cloudstream3.fork

import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.ui.settings.AssetPage
import org.json.JSONArray
import org.json.JSONObject

/** Plus > +18 Kategoriler: the page (assets/adult.html) and what it can ask the app. */
object AdultPage {
    class Bridge(private val activity: FragmentActivity) {
        private val c get() = activity

        private fun locked() = Adult.hasPin(c) && !Adult.unlocked

        @JavascriptInterface
        fun data(): String {
            val o = JSONObject()
                .put("on", Adult.on(c))
                .put("pin", Adult.hasPin(c))
                .put("locked", locked())
                .put("hasKey", GenreRows.canWork(c))
            if (!locked()) {
                val chosen = Adult.chosenCats(c)
                o.put("cats", JSONArray(Adult.cats.map {
                    JSONObject().put("name", it.name).put("des", it.des).put("on", it.name in chosen).put("tmdb", it.needsTmdb)
                }))
                o.put("seen", JSONArray(Adult.seen(c).map {
                    JSONObject().put("api", it.api).put("name", it.name).put("count", it.count).put("on", Adult.rowOn(c, it.api, it.name))
                }))
                o.put("mine", JSONArray(GenreRows.customs(c).filter { it.adult }.map {
                    JSONObject().put("name", it.name).put("summary", it.summary())
                }))
            }
            return o.toString()
        }

        @JavascriptInterface
        fun setOn(on: Boolean) {
            if (locked()) return
            Adult.setOn(c, on)
        }

        @JavascriptInterface
        fun setCat(name: String?, on: Boolean) {
            if (locked() || name == null) return
            Adult.setCat(c, name, on)
        }

        @JavascriptInterface
        fun setRow(api: String?, name: String?, on: Boolean) {
            if (locked() || api == null || name == null) return
            Adult.setRow(c, api, name, on)
        }

        @JavascriptInterface
        fun unlock(pin: String?): Boolean = Adult.unlock(c, pin.orEmpty())

        /** A new PIN (4–8 digits); only while unlocked. */
        @JavascriptInterface
        fun setPin(pin: String?): Boolean {
            if (locked()) return false
            val p = pin?.trim().orEmpty()
            if (p.length !in 4..8 || !p.all { it.isDigit() }) return false
            Adult.setPin(c, p)
            return true
        }

        @JavascriptInterface
        fun removePin(): Boolean {
            if (locked()) return false
            Adult.setPin(c, null)
            return true
        }

        @JavascriptInterface
        fun lock() {
            Adult.unlocked = false
            Adult.redraw()
        }

        @JavascriptInterface
        fun addCustom(name: String?, words: String?): Boolean {
            if (locked()) return false
            val n = name?.trim()?.take(40).orEmpty()
            val w = words.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (n.isEmpty()) return false
            GenreRows.saveCustom(c, GenreRows.Custom(n, words = w, adult = true))
            Adult.redraw()
            return true
        }

        @JavascriptInterface
        fun removeCustom(name: String?) {
            if (locked() || name == null) return
            GenreRows.removeCustom(c, name)
            Adult.redraw()
        }
    }

    fun show(activity: FragmentActivity) {
        AssetPage.show(activity, "adult.html", bridges = mapOf("Adult" to Bridge(activity)))
    }
}
