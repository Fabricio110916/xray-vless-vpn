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
            
            // EXCLUIR APP DA VPN (previne loop)
            try {
                builder.addDisallowedApplication(packageName)
                LogManager.addLog("✅ App excluído: $packageName")
            } catch (e: Exception) {
                LogManager.addLog("⚠️ Excluir app: ${e.message}")
            }
            
            vpnInterface = builder.establish()
            if (vpnInterface == null) { stopSelf(); return START_NOT_STICKY }
            
            // PROTEGER SOCKET SOCKS (ANTES de iniciar Tun2Socks)
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                val ok = protect(sock)
                LogManager.addLog("🔒 SOCKS protegido: $ok")
                sock.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ SOCKS: ${e.message}")
            }
            
            // Proteger DNS também
            try {
                val dns = Socket()
                dns.connect(InetSocketAddress("1.1.1.1", 53), 3000)
                protect(dns)
                dns.close()
                LogManager.addLog("🔒 DNS 1.1.1.1 protegido")
            } catch (e: Exception) {}
            
            try {
                val dns = Socket()
                dns.connect(InetSocketAddress("8.8.8.8", 53), 3000)
                protect(dns)
                dns.close()
                LogManager.addLog("🔒 DNS 8.8.8.8 protegido")
            } catch (e: Exception) {}
            
            running = true
            
            // Obter FD
            var fd = -1
            try {
                val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
                m.isAccessible = true
                fd = m.invoke(vpnInterface) as Int
            } catch (e: Exception) {}
            if (fd < 0) try { fd = vpnInterface!!.detachFd() } catch (e: Exception) {}
            if (fd < 0) try {
                val f = FileDescriptor::class.java.getDeclaredField("fd")
                f.isAccessible = true
                fd = f.getInt(vpnInterface!!.fileDescriptor)
            } catch (e: Exception) {}
            
            LogManager.addLog("FD=$fd Tun=${Tun2SocksJNI.isAvailable()}")
            
            if (fd >= 0 && Tun2SocksJNI.isAvailable()) {
                val finalFd = fd
                Thread({
                    LogManager.addLog("Tun2Socks thread iniciando...")
                    try {
                        Tun2SocksJNI.StartTun2socks(finalFd, "127.0.0.1:${XrayCoreService.SOCKS_PORT}", 1500)
                        LogManager.addLog("Tun2Socks retornou")
                    } catch (e: Exception) {
                        LogManager.addLog("❌ Tun2Socks: ${e.message}")
                    }
                }).start()
                LogManager.addLog("✅ VPN ativa!")
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
