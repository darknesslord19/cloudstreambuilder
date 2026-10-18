package com.lagradost.cloudstream3.fork.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Turns what the user pastes (a WireGuard .conf, or a vless:// vmess:// trojan:// ss:// link)
 * into an Xray outbound, and builds the whole Xray config around it: a local SOCKS port that only
 * this app uses (an HTTP proxy port: OkHttp speaks it itself on every Android version).
 */
object VpnConfig {

    class Parsed(val kind: String, val name: String, val host: String, val port: Int, val outbound: JSONObject)

    // ---------------------------------------------------------------- small helpers

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (t: Throwable) {
        s
    }

    fun b64(s: String): String? {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_PADDING, Base64.NO_PADDING, Base64.URL_SAFE)) {
            try {
                val pad = if (clean.length % 4 == 0) clean else clean + "=".repeat(4 - clean.length % 4)
                return String(Base64.decode(pad, flags), Charsets.UTF_8)
            } catch (t: Throwable) {
            }
        }
        return null
    }

    private class Link(val scheme: String, val user: String, val host: String, val port: Int, val query: Map<String, String>, val name: String)

    /** scheme://user@host:port?query#name, also with [ipv6] hosts */
    private fun splitLink(raw: String): Link? {
        val s = raw.trim()
        val schemeEnd = s.indexOf("://").takeIf { it > 0 } ?: return null
        val scheme = s.substring(0, schemeEnd).lowercase()
        var rest = s.substring(schemeEnd + 3)
        var name = ""
        rest.indexOf('#').takeIf { it >= 0 }?.let { name = decode(rest.substring(it + 1)); rest = rest.substring(0, it) }
        var query = ""
        rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }
        rest = rest.trimEnd('/')
        val at = rest.lastIndexOf('@')
        val user = if (at >= 0) decode(rest.substring(0, at)) else ""
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            host = hostPort.substring(1, close)
            port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
        } else {
            val c = hostPort.lastIndexOf(':')
            host = if (c > 0) hostPort.substring(0, c) else hostPort
            port = if (c > 0) hostPort.substring(c + 1).toIntOrNull() ?: 443 else 443
        }
        val q = LinkedHashMap<String, String>()
        query.split('&').filter { it.isNotBlank() }.forEach { p ->
            val e = p.indexOf('=')
            if (e > 0) q[p.substring(0, e).lowercase()] = decode(p.substring(e + 1)) else q[p.lowercase()] = ""
        }
        return Link(scheme, user, host, port, q, name)
    }

    /** streamSettings from the usual share link parameters */
    private fun stream(q: Map<String, String>, host: String, defaultSecurity: String): JSONObject {
        val network = when (q["type"]?.lowercase()) {
            null, "", "tcp", "raw" -> "raw"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            else -> q["type"]!!.lowercase()
        }
        val security = (q["security"]?.lowercase()?.takeIf { it.isNotBlank() } ?: defaultSecurity).let { if (it == "none") "none" else it }
        val s = JSONObject().put("network", network).put("security", security)
        val sni = q["sni"] ?: q["peer"] ?: q["servername"]
        val fp = q["fp"]?.takeIf { it.isNotBlank() }
        val alpn = q["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        when (security) {
            "tls" -> {
                val t = JSONObject().put("serverName", sni?.takeIf { it.isNotBlank() } ?: q["host"]?.takeIf { it.isNotBlank() } ?: host)
                if (fp != null) t.put("fingerprint", fp)
                if (!alpn.isNullOrEmpty()) t.put("alpn", JSONArray(alpn))
                s.put("tlsSettings", t)
            }
            "reality" -> {
                val r = JSONObject().put("serverName", sni ?: "").put("fingerprint", fp ?: "chrome")
                    .put("password", q["pbk"] ?: "").put("shortId", q["sid"] ?: "")
                q["spx"]?.let { r.put("spiderX", it) }
                s.put("realitySettings", r)
            }
        }
        val path = q["path"]
        val hostHeader = q["host"]?.takeIf { it.isNotBlank() }
        when (network) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", path ?: "/").also { o -> hostHeader?.let { o.put("host", it) } })
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", path ?: "/").also { o -> hostHeader?.let { o.put("host", it) } })
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", q["servicename"] ?: path ?: "")
                .put("multiMode", q["mode"] == "multi"))
            "xhttp" -> s.put("xhttpSettings", JSONObject().put("path", path ?: "/").put("mode", q["mode"] ?: "auto")
                .also { o -> hostHeader?.let { o.put("host", it) } })
            "raw" -> if (q["headertype"] == "http") {
                s.put("rawSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request",
                    JSONObject().put("path", JSONArray().put(path ?: "/"))
                        .put("headers", JSONObject().put("Host", JSONArray().put(hostHeader ?: host))))))
            }
        }
        return s
    }

    // ---------------------------------------------------------------- the kinds

    fun parse(text: String): Parsed? {
        val t = text.trim()
        return try {
            when {
                t.contains("[Interface]", true) -> wireguard(t)
                t.startsWith("vless://", true) -> vless(t)
                t.startsWith("vmess://", true) -> vmess(t)
                t.startsWith("trojan://", true) -> trojan(t)
                t.startsWith("ss://", true) -> shadowsocks(t)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun vless(raw: String): Parsed? {
        val l = splitLink(raw) ?: return null
        if (l.user.isBlank() || l.host.isBlank()) return null
        val settings = JSONObject().put("address", l.host).put("port", l.port).put("id", l.user)
            .put("encryption", l.query["encryption"]?.takeIf { it.isNotBlank() } ?: "none")
        l.query["flow"]?.takeIf { it.isNotBlank() }?.let { settings.put("flow", it) }
        val out = JSONObject().put("protocol", "vless").put("settings", settings).put("streamSettings", stream(l.query, l.host, "none"))
        return Parsed("VLESS", l.name.ifBlank { l.host }, l.host, l.port, out)
    }

    private fun trojan(raw: String): Parsed? {
        val l = splitLink(raw) ?: return null
        if (l.user.isBlank() || l.host.isBlank()) return null
        val settings = JSONObject().put("address", l.host).put("port", l.port).put("password", l.user)
        val out = JSONObject().put("protocol", "trojan").put("settings", settings).put("streamSettings", stream(l.query, l.host, "tls"))
        return Parsed("Trojan", l.name.ifBlank { l.host }, l.host, l.port, out)
    }

    private fun vmess(raw: String): Parsed? {
        val body = raw.substring(8).substringBefore('#')
        val json = JSONObject(b64(body) ?: return null)
        val host = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 443)
        if (host.isBlank()) return null
        val q = HashMap<String, String>()
        q["type"] = json.optString("net", "tcp")
        q["security"] = json.optString("tls", "").ifBlank { "none" }
        json.optString("sni").takeIf { it.isNotBlank() }?.let { q["sni"] = it }
        json.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
        json.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it; q["servicename"] = it }
        json.optString("fp").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
        json.optString("alpn").takeIf { it.isNotBlank() }?.let { q["alpn"] = it }
        json.optString("type").takeIf { it.isNotBlank() }?.let { q["headertype"] = it; if (q["type"] == "grpc") q["mode"] = it }
        val settings = JSONObject().put("address", host).put("port", port).put("id", json.optString("id"))
            .put("security", json.optString("scy").ifBlank { "auto" })
        val out = JSONObject().put("protocol", "vmess").put("settings", settings).put("streamSettings", stream(q, host, "none"))
        return Parsed("VMess", json.optString("ps").ifBlank { host }, host, port, out)
    }

    private fun shadowsocks(raw: String): Parsed? {
        var l = splitLink(raw) ?: return null
        var method: String
        var password: String
        if (l.user.isBlank()) {
            // ss://BASE64(method:password@host:port)#name
            val inner = b64(raw.substring(5).substringBefore('#').substringBefore('?')) ?: return null
            l = splitLink("ss://$inner" + (if (l.name.isNotBlank()) "#" + l.name else "")) ?: return null
            method = l.user.substringBefore(':')
            password = l.user.substringAfter(':')
        } else {
            val userInfo = if (l.user.contains(':')) l.user else b64(l.user) ?: return null
            method = userInfo.substringBefore(':')
            password = userInfo.substringAfter(':')
        }
        if (method.isBlank() || l.host.isBlank()) return null
        val settings = JSONObject().put("address", l.host).put("port", l.port).put("method", method).put("password", password)
        val out = JSONObject().put("protocol", "shadowsocks").put("settings", settings)
        return Parsed("Shadowsocks", l.name.ifBlank { l.host }, l.host, l.port, out)
    }

    /** A WireGuard .conf: [Interface] PrivateKey, Address, MTU, (Reserved); [Peer] PublicKey, PresharedKey, Endpoint, AllowedIPs */
    private fun wireguard(text: String): Parsed? {
        var section = ""
        val iface = HashMap<String, String>()
        val peer = HashMap<String, String>()
        var name = ""
        text.lines().forEach { line0 ->
            val line = line0.trim()
            if (line.startsWith("#")) {
                if (name.isBlank()) name = line.removePrefix("#").trim().removePrefix("Name").removePrefix("name").trim(' ', '=', ':')
                return@forEach
            }
            if (line.startsWith("[")) {
                section = line.lowercase(); return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val k = line.substring(0, eq).trim().lowercase()
            val v = line.substring(eq + 1).trim()
            if (section.contains("interface")) iface[k] = v else if (section.contains("peer") && !peer.containsKey(k)) peer[k] = v
        }
        val key = iface["privatekey"] ?: return null
        val pub = peer["publickey"] ?: return null
        val endpoint = peer["endpoint"] ?: return null
        val host = if (endpoint.startsWith("[")) endpoint.substring(1, endpoint.indexOf(']')) else endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: 51820
        val p = JSONObject().put("publicKey", pub).put("endpoint", endpoint)
        peer["presharedkey"]?.let { p.put("preSharedKey", it) }
        peer["persistentkeepalive"]?.toIntOrNull()?.let { p.put("keepAlive", it) }
        val addresses = (iface["address"] ?: "10.0.0.2/32").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.contains('/')) it else if (it.contains(':')) "$it/128" else "$it/32" }
        val settings = JSONObject().put("secretKey", key).put("address", JSONArray(addresses)).put("peers", JSONArray().put(p))
            .put("mtu", iface["mtu"]?.toIntOrNull() ?: 1280).put("noKernelTun", true)
        reservedOf(iface["reserved"] ?: peer["reserved"])?.let { settings.put("reserved", it) }
        return Parsed("WireGuard", name.ifBlank { host }, host, port, JSONObject().put("protocol", "wireguard").put("settings", settings))
    }

    /** "1,2,3" or a base64 client id */
    fun reservedOf(v: String?): JSONArray? {
        v ?: return null
        val nums = v.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (nums.size == 3) return JSONArray(nums)
        val bytes = try {
            Base64.decode(v.trim(), Base64.DEFAULT)
        } catch (t: Throwable) {
            null
        } ?: return null
        return if (bytes.size == 3) JSONArray(bytes.map { it.toInt() and 0xff }) else null
    }

    // ---------------------------------------------------------------- the whole config

    private fun dnsOf(server: String) = JSONObject()
        .put("servers", JSONArray().put(server).put(if (server.contains("8.8.8.8")) "https+local://1.1.1.1/dns-query" else "https+local://8.8.8.8/dns-query"))
        .put("queryStrategy", "UseIPv4")

    private fun httpIn(tag: String, port: Int) = JSONObject()
        .put("tag", tag).put("listen", "127.0.0.1").put("port", port).put("protocol", "http").put("settings", JSONObject())

    /** The port the phone-wide VPN (hev-socks5-tunnel) sends the other apps' packets to. */
    private fun socksIn(port: Int) = JSONObject()
        .put("tag", "dev").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
        .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
        .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))

    private fun dnsRule() = JSONObject().put("type", "field").put("inboundTag", JSONArray().put("dev")).put("port", "53").put("outboundTag", "dns-out")

    fun full(outbound: JSONObject, port: Int, socksPort: Int = 0): String {
        val out = JSONObject(outbound.toString()).put("tag", "proxy")
        val inbounds = JSONArray().put(httpIn("in", port))
        if (socksPort > 0) inbounds.put(socksIn(socksPort))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", dnsOf("https+local://1.1.1.1/dns-query"))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray().put(out).put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "dns-out").put("protocol", "dns")))
            .put("routing", JSONObject().put("rules", JSONArray().put(dnsRule())))
            .toString()
    }

    /**
     * DPI aşma: Xray finds the address of a site over HTTPS (the operator's DNS may lie), then
     * connects to it through ByeDPI (a SOCKS program on [dpiPort]). With [warp], a second port
     * ([warpPort]) goes through WARP, for the sites ByeDPI cannot open.
     */
    fun dpi(port: Int, socksPort: Int, dpiPort: Int, dns: String, warp: JSONObject?, warpPort: Int): String {
        val inbounds = JSONArray().put(httpIn("in", port))
        if (socksPort > 0) inbounds.put(socksIn(socksPort))
        if (warp != null && warpPort > 0) inbounds.put(httpIn("in-warp", warpPort))
        val outbounds = JSONArray()
            .put(JSONObject().put("tag", "dpi-out").put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", "UseIPv4"))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "byedpi"))))
            .put(JSONObject().put("tag", "byedpi").put("protocol", "socks")
                .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", dpiPort)))))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject().put("domainStrategy", "UseIPv4")))
            .put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        if (warp != null && warpPort > 0) outbounds.put(JSONObject(warp.toString()).put("tag", "warp"))
        val rules = JSONArray().put(dnsRule())
            // UDP (games, calls, QUIC) is not changed by ByeDPI: it goes straight
            .put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("dev")).put("network", "udp").put("outboundTag", "direct"))
        if (warp != null && warpPort > 0) rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in-warp")).put("outboundTag", "warp"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", dnsOf(dns))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }
}
