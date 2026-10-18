package com.lagradost.cloudstream3.fork.vpn

import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.ui.settings.AssetPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Plus > VPN: the page (assets/vpn.html) and what it can ask the app. */
object VpnPage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Bridge(private val activity: FragmentActivity) {
        @Volatile
        var handle: AssetPage.Handle? = null

        private fun js(script: String) = handle?.runScript(script)

        private fun result(token: Int, what: String, ok: Boolean, message: String) {
            val json = JSONObject().put("what", what).put("ok", ok).put("message", message).toString()
            js("window.onVpnResult && window.onVpnResult($token, ${JSONObject.quote(json)})")
        }

        private fun stateChanged() = js("window.onVpnState && window.onVpnState()")

        @JavascriptInterface
        fun data(): String {
            val c = activity
            val admin = Vpn.adminServer(c)
            return JSONObject()
                .put("supported", Vpn.supported(c))
                .put("version", Vpn.coreVersion(c))
                .put("running", Vpn.running)
                .put("since", Vpn.since)
                .put("bytes", Vpn.sessionBytes())
                .put("mode", Vpn.mode(c).let { if (it == Vpn.MODE_ADMIN && admin == null) Vpn.MODE_WARP else it })
                .put("scope", Vpn.scopeOf(c))
                .put("auto", Vpn.flag(c, "auto", false))
                .put("smart", Vpn.flag(c, "smart", true))
                .put("failopen", Vpn.flag(c, "failopen", true))
                .put("servers", JSONArray(Vpn.servers(c).map {
                    JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("host", it.host).put("port", it.port)
                }))
                .put("selected", Vpn.selected(c) ?: Vpn.servers(c).firstOrNull()?.id ?: JSONObject.NULL)
                .put("admin", admin?.let { JSONObject().put("name", it.first) } ?: JSONObject.NULL)
                .put("warp", JSONObject().put("has", Warp.hasAccount(c)).put("plus", Warp.plus(c)))
                .put("learned", Vpn.learnedCount())
                .put("error", Vpn.lastError ?: JSONObject.NULL)
                .toString()
        }

        @JavascriptInterface
        fun toggle() {
            scope.launch {
                if (Vpn.running) Vpn.stop() else Vpn.start(activity)
                stateChanged()
            }
        }

        private fun restartIfRunning() {
            if (!Vpn.running) return
            scope.launch {
                Vpn.restart(activity)
                stateChanged()
            }
        }

        @JavascriptInterface
        fun setMode(mode: String?) {
            if (mode !in listOf(Vpn.MODE_WARP, Vpn.MODE_OWN, Vpn.MODE_ADMIN)) return
            Vpn.set(activity, "mode", mode!!)
            restartIfRunning()
        }

        @JavascriptInterface
        fun setScope(value: String?) {
            if (value !in listOf(Vpn.SCOPE_SMART, Vpn.SCOPE_SITES, Vpn.SCOPE_ALL)) return
            Vpn.set(activity, "scope", value!!)
        }

        @JavascriptInterface
        fun setFlag(key: String?, on: Boolean) {
            if (key !in listOf("auto", "smart", "failopen")) return
            Vpn.set(activity, key!!, on)
        }

        @JavascriptInterface
        fun addServer(token: Int, raw: String?, name: String?) {
            val text = raw?.trim().orEmpty()
            val s = if (text.isEmpty()) null else Vpn.addServer(activity, text, name)
            if (s == null) {
                result(token, "add", false, "Tanınmadı · WireGuard .conf ya da vless:// vmess:// trojan:// ss:// olmalı")
            } else {
                result(token, "add", true, "${s.kind} sunucusu eklendi · ${s.name}")
                if (Vpn.mode(activity) == Vpn.MODE_OWN) restartIfRunning()
            }
        }

        @JavascriptInterface
        fun removeServer(id: String?) {
            id ?: return
            val was = Vpn.selected(activity) == id
            Vpn.removeServer(activity, id)
            if (was && Vpn.mode(activity) == Vpn.MODE_OWN) restartIfRunning()
        }

        @JavascriptInterface
        fun select(id: String?) {
            id ?: return
            if (Vpn.selected(activity) == id) return
            Vpn.select(activity, id)
            if (Vpn.mode(activity) == Vpn.MODE_OWN) restartIfRunning()
        }

        @JavascriptInterface
        fun ping() {
            scope.launch {
                Vpn.servers(activity).forEach { s ->
                    val ms = Vpn.delay(activity, Vpn.MODE_OWN, s.id)
                    js("window.onVpnPing && window.onVpnPing(${JSONObject.quote(s.id)}, $ms)")
                }
            }
        }

        @JavascriptInterface
        fun test() {
            scope.launch {
                val start = System.currentTimeMillis()
                val r = Vpn.test()
                if (Vpn.running && r.optJSONObject("vpn")?.has("ip") == true) {
                    r.put("ping", (System.currentTimeMillis() - start) / 2)
                }
                js("window.onVpnTest && window.onVpnTest(${JSONObject.quote(r.toString())})")
            }
        }

        @JavascriptInterface
        fun warpLicense(token: Int, key: String?) {
            val k = key?.trim().orEmpty()
            if (k.length < 20) {
                result(token, "warp", false, "Anahtar 26 karakter olmalı")
                return
            }
            scope.launch {
                val why = Warp.setLicense(activity, k)
                result(token, "warp", why == null, why ?: "WARP+ etkin")
                if (why == null && Vpn.mode(activity) == Vpn.MODE_WARP) restartIfRunning()
            }
        }

        @JavascriptInterface
        fun clearLearned() = Vpn.clearLearned(activity)
    }

    fun show(activity: FragmentActivity) {
        val bridge = Bridge(activity)
        bridge.handle = AssetPage.show(activity, "vpn.html", bridges = mapOf("Vpn" to bridge))
    }
}
