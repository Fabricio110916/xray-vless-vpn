package com.vpn.xrayvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.FileDescriptor
import java.net.*

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var xray: XrayCoreService? = null

    override fun onBind(i: Intent?) = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        try {
            LogManager.init(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Exception) {}
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val notification = NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build()
            
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }

            val json = i?.getStringExtra("vless_config") ?: run { stopSelf(); return START_NOT_STICKY }
            val c = Gson().fromJson(json, VlessConfig::class.java)
            
            // 1. Iniciar Xray
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { stopSelf(); return START_NOT_STICKY }
            
            // 2. Proteger socket SOCKS ANTES da VPN (usando socket normal)
            Thread {
                try {
                    Thread.sleep(500)
                    val s = Socket()
                    s.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                    LogManager.addLog("🔒 SOCKS conectado: ${s.isConnected}")
                    s.close()
                } catch (e: Exception) {
                    LogManager.addLog("⚠️ SOCKS pre: ${e.message}")
                }
            }.start()
            
            // 3. Criar VPN
            val builder = Builder()
                .setSession(c.remark)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpnInterface = builder.establish()
            if (vpnInterface == null) { stopSelf(); return START_NOT_STICKY }
            LogManager.addLog("✅ VPN estabelecida")
            
            // 4. Proteger via DatagramChannel (mais confiável)
            try {
                val dc = DatagramChannel.open()
                dc.socket().bind(InetSocketAddress(0))
                dc.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT))
                val ok = protect(dc.socket())
                LogManager.addLog("🔒 SOCKS protect(DatagramChannel)=$ok")
                dc.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ DC: ${e.message}")
            }
            
            // 5. Proteger via Socket normal
            try {
                val sock = Socket()
                sock.bind(InetSocketAddress(0))
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                val ok = protect(sock)
                LogManager.addLog("🔒 SOCKS protect(Socket)=$ok")
                sock.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ Socket: ${e.message}")
            }
            
            running = true
            
            // 6. Obter FD
            var fd = -1
            try {
                val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
                m.isAccessible = true
                fd = m.invoke(vpnInterface) as Int
            } catch (e: Exception) {}
            
            LogManager.addLog("📎 FD=$fd Tun=${Tun2SocksJNI.isAvailable()}")
            
            if (fd > 0 && Tun2SocksJNI.isAvailable()) {
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    Thread.currentThread().setUncaughtExceptionHandler { _, ex ->
                        LogManager.addLog("❌ TUN: ${ex.message}")
                    }
                    LogManager.addLog("🚀 StartTun2socks(fd=$fd)")
                    Tun2SocksJNI.StartTun2socks(fd, "127.0.0.1:${XrayCoreService.SOCKS_PORT}", 1500)
                }, "TUN").start()
                LogManager.addLog("✅ VPN ATIVA!")
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { Tun2SocksJNI.StopTun2socks() } catch (e: Exception) {}
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
