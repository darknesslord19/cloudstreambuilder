package com.lagradost.cloudstream3.fork.vpn

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A free Cloudflare WARP account, made the way the WARP app does it: a WireGuard key is made here,
 * Cloudflare answers with its own key, the addresses and the "reserved" bytes. Kept in the
 * preferences, so it is made once.
 */
object Warp {
    private const val PREFS = "fork_vpn_warp"
    private const val API = "https://api.cloudflareclient.com/v0a2158"
    private val json = "application/json; charset=UTF-8".toMediaType()

    // Cloudflare's WARP peer, the same for everyone
    private const val PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val ENDPOINT = "162.159.192.1:2408"

    private val client by lazy {
        // a plain client: this must not go through the tunnel it is making
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun account(context: Context): JSONObject? = try {
        prefs(context).getString("account", null)?.let { JSONObject(it) }
    } catch (t: Throwable) {
        null
    }

    fun hasAccount(context: Context) = account(context) != null

    fun plus(context: Context): Boolean = account(context)?.optBoolean("plus") == true

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val b = Request.Builder().url(API + path)
            .header("User-Agent", "okhttp/3.12.1")
            .header("CF-Client-Version", "a-6.30-2158")
        token?.let { b.header("Authorization", "Bearer $it") }
        val rb = (body?.toString() ?: "").toRequestBody(json)
        when (method) {
            "POST" -> b.post(rb)
            "PATCH" -> b.patch(rb)
            "PUT" -> b.put(rb)
            else -> b.get()
        }
        client.newCall(b.build()).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) throw IllegalStateException("WARP: HTTP ${res.code}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /** Makes the account when there is none. Throws with a short reason when it cannot. */
    fun ensure(context: Context): JSONObject {
        account(context)?.let { return it }
        val private = X25519.newPrivateKey()
        val public = X25519.publicKey(private)
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val reg = request(
            "POST", "/reg", JSONObject()
                .put("key", Base64.encodeToString(public, Base64.NO_WRAP))
                .put("install_id", "").put("fcm_token", "").put("tos", tos)
                .put("type", "Android").put("model", "CloudStream").put("locale", "en_US").put("warp_enabled", true), null
        )
        val id = reg.optString("id")
        val token = reg.optString("token")
        val config = reg.optJSONObject("config") ?: throw IllegalStateException("WARP: boş cevap")
        val peer = config.optJSONArray("peers")?.optJSONObject(0)
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
        val acc = JSONObject()
            .put("id", id).put("token", token)
            .put("private", Base64.encodeToString(private, Base64.NO_WRAP))
            .put("peer", peer?.optString("public_key")?.ifBlank { null } ?: PEER_KEY)
            .put("v4", addresses?.optString("v4")?.ifBlank { null } ?: "172.16.0.2")
            .put("v6", addresses?.optString("v6") ?: "")
            .put("reserved", config.optString("client_id"))
            .put("plus", false)
        try {
            request("PATCH", "/reg/$id", JSONObject().put("warp_enabled", true), token)
        } catch (t: Throwable) {
            // registering with warp_enabled is usually enough
        }
        prefs(context).edit().putString("account", acc.toString()).apply()
        return acc
    }

    /** WARP+ key (26 characters). Null when it was taken, otherwise the reason. */
    fun setLicense(context: Context, license: String): String? {
        val acc = try {
            ensure(context)
        } catch (t: Throwable) {
            return t.message ?: "WARP hesabı açılamadı"
        }
        return try {
            request("PUT", "/reg/${acc.optString("id")}/account", JSONObject().put("license", license.trim()), acc.optString("token"))
            val info = request("GET", "/reg/${acc.optString("id")}/account", null, acc.optString("token"))
            val plus = info.optString("account_type").let { it == "unlimited" || it == "limited" } || info.optBoolean("warp_plus")
            acc.put("plus", plus).put("license", license.trim())
            prefs(context).edit().putString("account", acc.toString()).apply()
            if (plus) null else "Anahtar alındı ama WARP+ görünmüyor"
        } catch (t: Throwable) {
            t.message ?: "Anahtar kabul edilmedi"
        }
    }

    fun reset(context: Context) = prefs(context).edit().clear().apply()

    fun outbound(context: Context): JSONObject {
        val acc = ensure(context)
        val addresses = JSONArray().put(acc.optString("v4") + "/32")
        acc.optString("v6").takeIf { it.isNotBlank() }?.let { addresses.put("$it/128") }
        val settings = JSONObject()
            .put("secretKey", acc.optString("private"))
            .put("address", addresses)
            .put("peers", JSONArray().put(JSONObject().put("publicKey", acc.optString("peer")).put("endpoint", ENDPOINT).put("keepAlive", 25)))
            .put("mtu", 1280)
            .put("noKernelTun", true)
        VpnConfig.reservedOf(acc.optString("reserved"))?.let { settings.put("reserved", it) }
        return JSONObject().put("protocol", "wireguard").put("settings", settings)
    }
}
