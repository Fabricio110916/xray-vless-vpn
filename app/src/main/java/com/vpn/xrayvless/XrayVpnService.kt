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
            
            // NÃO usar detachFd() - ele invalida o ParcelFileDescriptor!
            var fd = -1
            try {
                val f = FileDescriptor::class.java.getDeclaredField("fd")
                f.isAccessible = true
                fd = f.getInt(vpnInterface!!.fileDescriptor)
                LogManager.addLog("FD via campo fd: $fd")
            } catch (e: Exception) {
                LogManager.addLog("campo fd falhou: ${e.message}")
            }
            
            // Tentar também getFd()
            if (fd < 0) {
                try {
                    val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
                    m.isAccessible = true
                    fd = m.invoke(vpnInterface) as Int
                    LogManager.addLog("FD via getFd: $fd")
                } catch (e: Exception) {
                    LogManager.addLog("getFd falhou: ${e.message}")
                }
            }
            
            LogManager.addLog("FD final=$fd Tun2Socks=${Tun2SocksJNI.isAvailable()}")
            
            if (fd >= 0 && Tun2SocksJNI.isAvailable()) {
                val finalFd = fd
                val thread = Thread({
                    // Configurar handler de exceção
                    Thread.currentThread().setUncaughtExceptionHandler { _, ex ->
                        LogManager.addLog("❌ CRASH na thread Tun2Socks: ${ex.javaClass.simpleName}: ${ex.message}")
                        LogManager.addLog("Stack: ${ex.stackTraceToString().take(300)}")
                    }
                    
                    LogManager.addLog(">>> StartTun2socks(fd=$finalFd, 127.0.0.1:${XrayCoreService.SOCKS_PORT}, 1500)")
                    try {
                        Tun2SocksJNI.StartTun2socks(finalFd, "127.0.0.1:${XrayCoreService.SOCKS_PORT}", 1500)
                        LogManager.addLog("<<< StartTun2socks retornou normalmente")
                    } catch (e: Exception) {
                        LogManager.addLog("❌ StartTun2socks exceção: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }, "Tun2Socks-Native")
                
                thread.setUncaughtExceptionHandler { _, ex ->
                    LogManager.addLog("❌ Thread Tun2Socks não capturada: ${ex.message}")
                }
                
                thread.start()
                LogManager.addLog("✅ Thread iniciada")
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LogManager.addLog("onDestroy")
        running = false
        try { Tun2SocksJNI.StopTun2socks() } catch (e: Exception) {}
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
