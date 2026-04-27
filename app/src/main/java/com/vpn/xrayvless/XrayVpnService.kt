package com.vpn.xrayvless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.net.InetSocketAddress
import java.net.Socket

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var xray: XrayCoreService? = null

    override fun onBind(i: Intent?) = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val n = NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)

            val c = Gson().fromJson(i?.getStringExtra("vless_config"), VlessConfig::class.java)

            // 1. Xray SOCKS5
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { stopSelf(); return START_NOT_STICKY }

            // 2. VPN
            val b = Builder()
                .setSession(c.remark).addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0).setMtu(1500).setBlocking(true)
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }

            // 3. PROTEGER SOCKS (ANTES do tun2socks!)
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                val ok = protect(sock)
                LogManager.addLog("?? SOCKS protect=$ok")
                sock.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ SOCKS: ${e.message}")
            }

            // 4. Proteger DNS também
            try {
                listOf("1.1.1.1", "8.8.8.8").forEach { dns ->
                    val s = Socket()
                    s.connect(InetSocketAddress(dns, 53), 3000)
                    val p = protect(s)
                    LogManager.addLog("?? DNS $dns=$p")
                    s.close()
                }
            } catch (e: Exception) {}

            // 5. Limpar CLOEXEC do fd
            val fd = vpnInterface!!.fd
            Tun2SocksJNI.clearCloexec(fd)
            LogManager.addLog("FD=$fd")

            // 6. Tun2Socks JNI
            if (Tun2SocksJNI.isAvailable()) {
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    Thread.currentThread().setUncaughtExceptionHandler { _, ex ->
                        LogManager.addLog("❌ TUN crash: ${ex.message}")
                    }
                    LogManager.addLog("?? StartTun2socks(fd=$fd)")
                    Tun2SocksJNI.StartTun2socks(fd, "127.0.0.1:${XrayCoreService.SOCKS_PORT}", 1500)
                }, "tun2socks").start()
                LogManager.addLog("✅ TUN JNI!")
            }

        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { Tun2SocksJNI.StopTun2socks() } catch (e: Exception) {}
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
