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
import java.io.File
import java.io.FileDescriptor
import java.net.*

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var xray: XrayCoreService? = null
    private var tunProcess: Process? = null

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
            
            // Proteger SOCKS
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                protect(sock)
                sock.close()
            } catch (e: Exception) {}
            
            running = true
            
            // Obter FD
            var fd = -1
            try {
                val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
                m.isAccessible = true
                fd = m.invoke(vpnInterface) as Int
            } catch (e: Exception) {}
            
            LogManager.addLog("📎 FD=$fd")
            
            if (fd > 0) {
                // Tentar com executável tun2socks-arm64 (se disponível)
                val tunExe = File(filesDir, "tun2socks")
                if (!tunExe.exists()) {
                    // Copiar do assets
                    try {
                        assets.open("tun2socks").use { input ->
                            tunExe.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tunExe.setExecutable(true)
                        LogManager.addLog("✅ tun2socks executável: ${tunExe.length()} bytes")
                    } catch (e: Exception) {
                        LogManager.addLog("⚠️ tun2socks não encontrado em assets")
                    }
                }
                
                if (tunExe.exists() && tunExe.canExecute()) {
                    Thread({
                        try {
                            val cmd = arrayOf(
                                tunExe.absolutePath,
                                "-fd", fd.toString(),
                                "-socks", "127.0.0.1:${XrayCoreService.SOCKS_PORT}",
                                "-mtu", "1500"
                            )
                            LogManager.addLog("🚀 ${cmd.joinToString(" ")}")
                            tunProcess = Runtime.getRuntime().exec(cmd)
                            
                            // Ler logs do tun2socks
                            tunProcess?.inputStream?.bufferedReader()?.use { reader ->
                                reader.lines().forEach { line ->
                                    if (line.isNotBlank()) LogManager.addLog("TUN: $line")
                                }
                            }
                        } catch (e: Exception) {
                            LogManager.addLog("❌ TUN exe: ${e.message}")
                        }
                    }, "tun2socks-exe").start()
                    LogManager.addLog("✅ TUN executável rodando!")
                } else {
                    // Fallback: usar JNI
                    if (Tun2SocksJNI.isAvailable()) {
                        Thread({
                            LogManager.addLog("🚀 StartTun2socks JNI(fd=$fd)")
                            Tun2SocksJNI.StartTun2socks(fd, "127.0.0.1:${XrayCoreService.SOCKS_PORT}", 1500)
                        }, "TUN-JNI").start()
                        LogManager.addLog("✅ TUN JNI rodando!")
                    }
                }
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        tunProcess?.destroy()
        try { Tun2SocksJNI.StopTun2socks() } catch (e: Exception) {}
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
