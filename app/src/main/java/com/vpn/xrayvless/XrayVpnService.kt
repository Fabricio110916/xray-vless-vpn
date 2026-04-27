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
    private var tunProcess: java.lang.Process? = null

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
            
            LogManager.addLog("FD=$fd")
            
            if (fd > 0) {
                // Usar o executável CLI tun2socks (copiar do assets)
                val tunExe = File(filesDir, "tun2socks")
                if (!tunExe.exists()) {
                    assets.open("tun2socks").use { it.copyTo(tunExe.outputStream()) }
                    tunExe.setExecutable(true)
                }
                
                // Também tentar do nativeLibraryDir
                val nativeExe = File(applicationInfo.nativeLibraryDir, "tun2socks-arm64")
                
                val exePath = if (nativeExe.exists()) nativeExe.absolutePath else tunExe.absolutePath
                
                LogManager.addLog("Exe: $exePath exists=${File(exePath).exists()}")
                
                val socksAddr = "127.0.0.1:${XrayCoreService.SOCKS_PORT}"
                
                Thread({
                    try {
                        val cmd = arrayOf(exePath, "-fd", fd.toString(), "-socks", socksAddr, "-mtu", "1500")
                        LogManager.addLog("🚀 ${cmd.joinToString(" ")}")
                        tunProcess = Runtime.getRuntime().exec(cmd)
                        
                        // Consumir streams em background
                        Thread({ tunProcess?.inputStream?.bufferedReader()?.use { it.readLine() } }).start()
                        Thread({ tunProcess?.errorStream?.bufferedReader()?.use { 
                            var line: String?
                            while (it.readLine().also { line = it } != null) {
                                if (line!!.isNotBlank()) LogManager.addLog("TUN: $line")
                            }
                        }}).start()
                        
                        LogManager.addLog("✅ TUN rodando!")
                        val exit = tunProcess?.waitFor() ?: -1
                        LogManager.addLog("TUN fim: $exit")
                    } catch (e: Exception) {
                        LogManager.addLog("TUN err: ${e.message}")
                    }
                }, "tun2socks").start()
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY  // NÃO usar START_STICKY - permite que o serviço pare
    }

    override fun onDestroy() {
        LogManager.addLog(">>> onDestroy")
        running = false
        tunProcess?.destroy()
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        LogManager.addLog("✅ VPN destruída")
        super.onDestroy()
    }
}
