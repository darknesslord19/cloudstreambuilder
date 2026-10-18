package com.lagradost.cloudstream3.fork.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lagradost.cloudstream3.R

/**
 * Plus > VPN > "Diğer uygulamaları da kapsa": an Android VPN for the whole phone. The packets of
 * the other apps go to hev-socks5-tunnel, which gives them to the SOCKS port of the VPN of the app
 * (Xray: DNS over HTTPS, then DPI aşma / WARP / the server). This app itself stays outside, its
 * requests already go through the VPN and its helpers must reach the internet directly.
 */
class ForkVpnService : VpnService() {
    companion object {
        private const val TAG = "ForkVpnService"
        private const val ACTION_START = "com.lagradost.cloudstream3.fork.vpn.START"
        private const val ACTION_STOP = "com.lagradost.cloudstream3.fork.vpn.STOP"
        private const val CHANNEL = "fork_vpn"
        private const val NOTE_ID = 0x7650

        @Volatile
        var active = false
            private set

        @Volatile
        var lastError: String? = null

        fun start(context: Context, socksPort: Int) {
            val i = Intent(context, ForkVpnService::class.java).setAction(ACTION_START).putExtra("port", socksPort)
            try {
                context.startService(i)
            } catch (t: Throwable) {
                lastError = "Telefon geneli VPN başlatılamadı"
                Log.w(TAG, "start", t)
            }
        }

        fun stop(context: Context) {
            if (!active) return
            try {
                context.startService(Intent(context, ForkVpnService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                close()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val port = intent.getIntExtra("port", 0)
                lastError = try {
                    open(port)
                    null
                } catch (t: Throwable) {
                    Log.w(TAG, "open", t)
                    close()
                    stopSelf()
                    t.message ?: "Telefon geneli VPN açılamadı"
                }
                Vpn.onState?.invoke()
            }
            else -> {
                // started again by the system (always-on VPN): the VPN of the app decides
                if (!Vpn.running) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun open(port: Int) {
        if (port <= 0) throw IllegalStateException("VPN kapalı")
        if (!HevTunnel.load()) throw IllegalStateException("Bu cihaz telefon geneli VPN'i desteklemiyor")
        close()
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(8500)
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
        try {
            b.addAddress("fd00::1", 128).addRoute("::", 0)
        } catch (t: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        val own = packageName
        val apps = Vpn.deviceApps(this) - own
        when (Vpn.deviceAppsMode(this)) {
            "only" -> {
                var any = false
                apps.forEach {
                    try {
                        b.addAllowedApplication(it); any = true
                    } catch (t: Throwable) {
                    }
                }
                if (!any) b.addDisallowedApplication(own)
            }
            "except" -> {
                b.addDisallowedApplication(own)
                apps.forEach {
                    try {
                        b.addDisallowedApplication(it)
                    } catch (t: Throwable) {
                    }
                }
            }
            else -> b.addDisallowedApplication(own)
        }
        val fd = b.establish() ?: throw IllegalStateException("VPN izni verilmedi")
        tun = fd
        val conf = java.io.File(filesDir, "xray/tunnel.yml").apply {
            parentFile?.mkdirs()
            writeText(
                "tunnel:\n  mtu: 8500\nmisc:\n  task-stack-size: 81920\n" +
                        "socks5:\n  address: 127.0.0.1\n  port: $port\n  udp: udp\n"
            )
        }
        HevTunnel.TProxyStartService(conf.absolutePath, fd.fd)
        active = true
        foreground()
    }

    @Synchronized
    private fun close() {
        if (active) {
            try {
                HevTunnel.TProxyStopService()
            } catch (t: Throwable) {
            }
        }
        active = false
        try {
            tun?.close()
        } catch (t: Throwable) {
        }
        tun = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        } catch (t: Throwable) {
        }
    }

    private fun foreground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
            }
            val pf = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, pf or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val stop = PendingIntent.getService(
                this, 1, Intent(this, ForkVpnService::class.java).setAction(ACTION_STOP), pf or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val note: Notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_fork_vpn_24)
                .setContentTitle(getString(R.string.vpn_device_note_title))
                .setContentText(getString(R.string.vpn_device_note_text, Vpn.modeName(this)))
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(0, getString(R.string.vpn_device_note_stop), stop)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTE_ID, note)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "foreground", t)
        }
    }

    override fun onRevoke() {
        // another VPN was turned on, or the user turned this one off in the settings
        close()
        Vpn.deviceRevoked(this)
        stopSelf()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }
}
