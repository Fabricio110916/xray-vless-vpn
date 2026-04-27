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
            val n = NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)

            val c = Gson().fromJson(i?.getStringExtra("vless_config"), VlessConfig::class.java)
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { stopSelf(); return START_NOT_STICKY }
            
            val b = Builder().setSession(c.remark).addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0).setMtu(1500).setBlocking(true)
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpnInterface = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }
            
            try { val sock = Socket(); sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000); protect(sock); sock.close() } catch (e: Exception) {}
            
            running = true
            
            var fd = -1
            try { val m = ParcelFileDescriptor::class.java.getDeclaredMethod("getFd"); m.isAccessible = true; fd = m.invoke(vpnInterface) as Int } catch (e: Exception) {}
            
            LogManager.addLog("FD=$fd")
            
            if (fd > 0) {
                val proxyAddr = "socks5://127.0.0.1:${XrayCoreService.SOCKS_PORT}"
                val nativeDir = applicationInfo.nativeLibraryDir
                val exeFile = File(nativeDir, "libtun2socks_exec.so")
                
                if (exeFile.exists()) {
                    Thread({
                        try {
                            // Argumentos corretos do tun2socks:
                            // -device fd://N -proxy socks5://host:port
                            val cmd = arrayOf(
                                "/system/bin/linker64",
                                exeFile.absolutePath,
                                "-device", "fd://$fd",
                                "-proxy", proxyAddr
                            )
                            LogManager.addLog("?? ${cmd.joinToString(" ")}")
                            val proc = Runtime.getRuntime().exec(cmd)
                            LogManager.addLog("✅ TUN!")
                            
                            Thread({ 
                                var l: String?
                                proc.errorStream.bufferedReader().use { 
                                    while (it.readLine().also { l = it } != null) {
                                        LogManager.addLog("TUN: $l")
                                    }
                                }
                            }).start()
                            
                            proc.waitFor()
                            LogManager.addLog("TUN fim: ${proc.exitValue()}")
                        } catch (e: Exception) { LogManager.addLog("TUN: ${e.message}") }
                    }, "tun").start()
                    LogManager.addLog("✅ TUN iniciado!")
                }
            }
        } catch (e: Exception) { LogManager.addLog("❌ ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { xray?.stop() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
