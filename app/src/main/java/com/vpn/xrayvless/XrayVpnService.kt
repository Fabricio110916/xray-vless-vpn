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
            LogManager.addLog("✅ Xray OK")
            
            val builder = Builder()
                .setSession(c.remark)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpnInterface = builder.establish()
            if (vpnInterface == null) { stopSelf(); return START_NOT_STICKY }
            
            LogManager.addLog("✅ VPN estabelecida")
            
            // Proteger SOCKS
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                protect(s)
                s.close()
            } catch (e: Exception) {}
            
            running = true
            
            // INICIAR TUN2SOCKS NATIVO
            if (Tun2SocksJNI.isAvailable()) {
                val fd = Tun2SocksJNI.getFd(vpnInterface!!.fileDescriptor)
                LogManager.addLog("Iniciando Tun2Socks nativo (fd=$fd)...")
                
                Thread {
                    Tun2SocksJNI.start(
                        fd,
                        "127.0.0.1:${XrayCoreService.SOCKS_PORT}",
                        1500
                    )
                }.start()
                
                LogManager.addLog("✅ Tun2Socks nativo rodando!")
            } else {
                LogManager.addLog("❌ libtun2socks.so não disponível!")
                LogManager.addLog("   Compile com: ./build_tun2socks_jni.sh")
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LogManager.addLog("Parando...")
        running = false
        Tun2SocksJNI.stop()
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        LogManager.addLog("✅ VPN parada")
        super.onDestroy()
    }
}
