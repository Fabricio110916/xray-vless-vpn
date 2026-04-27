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
            
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { stopSelf(); return START_NOT_STICKY }
            LogManager.addLog("✅ Xray OK")
            
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
            
            // Proteger socket SOCKS (IMPORTANTE: fazer DEPOIS do establish)
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                val result = protect(sock)
                LogManager.addLog("🔒 SOCKS protect=$result")
                sock.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ SOCKS: ${e.message}")
            }
            
            running = true
            
            // Obter FD
            var fd = -1
            try {
                val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
                m.isAccessible = true
                fd = m.invoke(vpnInterface) as Int
                LogManager.addLog("📎 FD=$fd")
            } catch (e: Exception) {
                LogManager.addLog("❌ FD: ${e.message}")
            }
            
            if (fd > 0 && Tun2SocksJNI.isAvailable()) {
                val finalFd = fd
                val socksAddr = "127.0.0.1:${XrayCoreService.SOCKS_PORT}"
                
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    Thread.currentThread().setUncaughtExceptionHandler { _, ex ->
                        LogManager.addLog("❌ TUN crash: ${ex.javaClass.simpleName}: ${ex.message}")
                        LogManager.addLog("Stack: ${ex.stackTraceToString().take(200)}")
                    }
                    
                    LogManager.addLog("🚀 StartTun2socks(fd=$finalFd, socks=$socksAddr)")
                    try {
                        Tun2SocksJNI.StartTun2socks(finalFd, socksAddr, 1500)
                    } catch (e: UnsatisfiedLinkError) {
                        LogManager.addLog("❌ JNI: ${e.message}")
                    } catch (e: Exception) {
                        LogManager.addLog("❌ TUN: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    LogManager.addLog("TUN finalizado")
                }, "TUN-Thread").start()
                
                LogManager.addLog("✅ VPN ATIVA!")
            } else {
                LogManager.addLog("❌ fd=$fd tun=${Tun2SocksJNI.isAvailable()}")
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ FATAL: ${e.message}")
            LogManager.addLog("Stack: ${e.stackTraceToString().take(200)}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LogManager.addLog("🛑 onDestroy")
        running = false
        try { Tun2SocksJNI.StopTun2socks() } catch (e: Exception) {}
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        LogManager.addLog("✅ VPN destruída")
        super.onDestroy()
    }
}
