package com.lagradost.cloudstream3.fork.vpn

/**
 * hev-socks5-tunnel (heiher, MIT), built as lib/<abi>/libhev-socks5-tunnel.so. It reads the
 * packets of the phone's VPN interface and sends them to a SOCKS port. Its JNI_OnLoad gives these
 * three functions to this class (built with PKGNAME=com/lagradost/cloudstream3/fork/vpn, CLSNAME=HevTunnel).
 */
object HevTunnel {
    @Volatile
    private var loaded: Boolean? = null

    fun load(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("hev-socks5-tunnel")
            true
        } catch (t: Throwable) {
            false
        }
        loaded = ok
        return ok
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray?
}
