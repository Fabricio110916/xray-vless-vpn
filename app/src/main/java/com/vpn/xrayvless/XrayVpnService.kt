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
import java.net.InetSocketAddress
import java.net.Socket

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
            
            // Proteger SOCKS
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                protect(sock)
                sock.close()
            } catch (e: Exception) {}
            
            running = true
            
            // Obter FD pelo método que funciona
            var fd = -1
            
            // Método 1: ParcelFileDescriptor.getFd()
            try {
                val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
                m.isAccessible = true
                fd = m.invoke(vpnInterface) as Int
                LogManager.addLog("📎 FD método 1: $fd")
            } catch (e: Exception) {
                LogManager.addLog("M1: ${e.message}")
            }
            
            // Método 2: detachFd
            if (fd < 0) {
                try {
                    fd = vpnInterface!!.detachFd()
                    LogManager.addLog("📎 FD método 2: $fd")
                } catch (e: Exception) {
                    LogManager.addLog("M2: ${e.message}")
                }
            }
            
            // Método 3: campo fd
            if (fd < 0) {
                try {
                    val f = FileDescriptor::class.java.getDeclaredField("fd")
                    f.isAccessible = true
                    fd = f.getInt(vpnInterface!!.fileDescriptor)
                    LogManager.addLog("📎 FD método 3: $fd")
                } catch (e: Exception) {
                    LogManager.addLog("M3: ${e.message}")
                }
            }
            
            if (fd >= 0 && Tun2SocksJNI.isAvailable()) {
                val finalFd = fd
                Thread({
                    Tun2SocksJNI.StartTun2socks(finalFd, "127.0.0.1:${XrayCoreService.SOCKS_PORT}", 1500)
                }, "Tun2Socks").start()
                LogManager.addLog("✅ Tun2Socks iniciado com fd=$finalFd")
            } else {
                LogManager.addLog("❌ FD=$fd Lib=${Tun2SocksJNI.isAvailable()}")
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
