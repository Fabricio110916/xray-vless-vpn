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
            
            // Iniciar Xray
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { 
                LogManager.addLog("❌ Xray falhou")
                stopSelf()
                return START_NOT_STICKY 
            }
            LogManager.addLog("✅ Xray SOCKS5:${XrayCoreService.SOCKS_PORT}")
            
            // Criar VPN
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
            if (vpnInterface == null) {
                LogManager.addLog("❌ VPN establish falhou")
                stopSelf()
                return START_NOT_STICKY
            }
            
            LogManager.addLog("✅ VPN estabelecida")
            
            // Proteger socket SOCKS
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                val prot = protect(sock)
                LogManager.addLog("?? SOCKS protegido: $prot")
                sock.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ SOCKS proteção: ${e.message}")
            }
            
            running = true
            
            // INICIAR TUN2SOCKS NATIVO
            if (Tun2SocksJNI.isAvailable()) {
                val fd = Tun2SocksJNI.getFd(vpnInterface!!.fileDescriptor)
                LogManager.addLog("?? Iniciando Tun2Socks nativo (fd=$fd)...")
                
                Thread({
                    LogManager.addLog("Thread Tun2Socks iniciada")
                    try {
                        Tun2SocksJNI.StartTun2socks(
                            fd,
                            "127.0.0.1:${XrayCoreService.SOCKS_PORT}",
                            1500
                        )
                    } catch (e: Exception) {
                        LogManager.addLog("❌ Tun2Socks crash: ${e.message}")
                    }
                    LogManager.addLog("Tun2Socks finalizado")
                }, "Tun2Socks-Native").start()
                
                LogManager.addLog("✅ Tun2Socks nativo rodando!")
            } else {
                LogManager.addLog("❌ libtun2socks.so não carregou!")
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ERRO: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LogManager.addLog("?? Parando VPN...")
        running = false
        
        try {
            Tun2SocksJNI.StopTun2socks()
            LogManager.addLog("✅ Tun2Socks parado")
        } catch (e: Exception) {
            LogManager.addLog("⚠️ Erro ao parar Tun2Socks: ${e.message}")
        }
        
        xray?.stop()
        
        try { vpnInterface?.close() } catch (e: Exception) {}
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        LogManager.addLog("✅ VPN completamente parada")
        super.onDestroy()
    }
}
