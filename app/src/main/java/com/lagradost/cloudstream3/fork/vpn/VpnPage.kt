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
                .put("dpi", dpiData())
                .put("device", JSONObject()
                    .put("available", Vpn.deviceAvailable(c))
                    .put("on", Vpn.deviceOn(c))
                    .put("active", Vpn.deviceActive())
                    .put("error", ForkVpnService.lastError ?: JSONObject.NULL)
                    .put("apps", Vpn.deviceAppsMode(c))
                    .put("list", JSONArray(Vpn.deviceApps(c).toList())))
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

        private fun dpiData(): JSONObject {
            val c = activity
            val cur = Dpi.current(c)
            return JSONObject()
                .put("available", Dpi.available(c))
                .put("supported", Dpi.supported(c))
                .put("preset", Dpi.preset(c))
                .put("presets", JSONArray(Dpi.presets.map { JSONObject().put("id", it.id).put("name", it.name).put("label", it.label) }))
                .put("found", Dpi.text(c, "dpi_found", "").isNotEmpty())
                .put("foundName", Dpi.text(c, "dpi_found_name", ""))
                .put("meth", Dpi.text(c, "dpi_meth", "split"))
                .put("pos", Dpi.text(c, "dpi_pos", "sni"))
                .put("ttl", Dpi.text(c, "dpi_ttl", "8"))
                .put("dns", Dpi.text(c, "dpi_dns", "cloudflare"))
                .put("cmd", Dpi.text(c, "dpi_cmd", ""))
                .put("warp", Vpn.flag(c, "dpi_warp", true))
                .put("args", cur.first)
                .put("name", cur.second)
                .put("finding", Dpi.finding)
        }

        @JavascriptInterface
        fun setDpi(key: String?, value: String?) {
            if (key !in listOf("dpi_preset", "dpi_meth", "dpi_pos", "dpi_ttl", "dpi_dns", "dpi_cmd")) return
            Vpn.set(activity, key!!, value?.trim()?.take(600) ?: "")
            if (key != "dpi_preset" && key != "dpi_dns") Vpn.set(activity, "dpi_preset", "adv")
            if (Vpn.mode(activity) == Vpn.MODE_DPI) restartIfRunning()
        }

        @JavascriptInterface
        fun find() {
            if (Dpi.finding) return
            scope.launch {
                val found = Dpi.find(activity) { row -> js("window.onDpiFind && window.onDpiFind(${JSONObject.quote(row.json().toString())})") }
                if (found != null && Vpn.mode(activity) == Vpn.MODE_DPI && Vpn.running) Vpn.restart(activity)
                stateChanged()
            }
        }

        @JavascriptInterface
        fun cancelFind() = Dpi.cancel()

        /** The switch "Diğer uygulamaları da kapsa": Android asks once for the VPN permission. */
        @JavascriptInterface
        fun setDevice(on: Boolean) {
            val c = activity
            if (!on) {
                Vpn.set(c, "device", false)
                Vpn.stopDevice(c)
                stateChanged()
                return
            }
            if (!Vpn.deviceAvailable(c)) return
            val ask = Vpn.devicePermission(c)
            if (ask == null) {
                Vpn.set(c, "device", true)
                Vpn.startDevice(c)
                stateChanged()
                return
            }
            activity.runOnUiThread {
                try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(ask, 0x7651)
                } catch (t: Throwable) {
                    result(0, "device", false, "VPN izni istenemedi")
                }
            }
            scope.launch {
                // the answer is seen when Android stops asking
                val until = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < until) {
                    kotlinx.coroutines.delay(700)
                    if (Vpn.devicePermission(c) == null) {
                        Vpn.set(c, "device", true)
                        Vpn.startDevice(c)
                        result(0, "device", true, "İzin verildi · diğer uygulamalar da VPN'den geçiyor")
                        stateChanged()
                        return@launch
                    }
                }
                result(0, "device", false, "VPN izni verilmedi")
            }
        }

        /** The apps of the phone that can be opened: [{pkg, name}] to window.onVpnApps. */
        @JavascriptInterface
        fun apps() {
            scope.launch {
                val pm = activity.packageManager
                val own = activity.packageName
                val list = try {
                    val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(i, 0).mapNotNull { it.activityInfo?.applicationInfo }
                        .distinctBy { it.packageName }.filter { it.packageName != own }
                        .map { it.packageName to (pm.getApplicationLabel(it)?.toString() ?: it.packageName) }
                        .sortedBy { it.second.lowercase() }
                } catch (t: Throwable) {
                    emptyList()
                }
                val a = JSONArray(list.map { JSONObject().put("pkg", it.first).put("name", it.second) })
                js("window.onVpnApps && window.onVpnApps(${JSONObject.quote(a.toString())})")
            }
        }

        @JavascriptInterface
        fun setApps(mode: String?, list: String?) {
            val m = mode?.takeIf { it in listOf("all", "only", "except") } ?: return
            val set = try {
                val a = JSONArray(list ?: "[]")
                (0 until a.length()).map { a.getString(it) }.toSet()
            } catch (t: Throwable) {
                emptySet()
            }
            Vpn.setDeviceApps(activity, m, set)
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
            if (mode !in listOf(Vpn.MODE_DPI, Vpn.MODE_WARP, Vpn.MODE_OWN, Vpn.MODE_ADMIN)) return
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
            if (key !in listOf("auto", "smart", "failopen", "dpi_warp")) return
            Vpn.set(activity, key!!, on)
            if (key == "dpi_warp" && Vpn.mode(activity) == Vpn.MODE_DPI) restartIfRunning()
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
