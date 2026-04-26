package com.vpn.xrayvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpn: ParcelFileDescriptor? = null
    private var running = false
    private var thread: Thread? = null
    private var xray: XrayCoreService? = null

    override fun onBind(i: Intent?) = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)
        LogManager.addLog("XrayVpnService onCreate")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            LogManager.addLog(">>> onStartCommand")
            
            val notification = NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN")
                .setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn)
                .setOngoing(true)
                .build()
            
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }

            val json = i?.getStringExtra("vless_config") ?: run {
                LogManager.addLog("❌ Sem config")
                stopSelf()
                return START_NOT_STICKY
            }
            
            val c = Gson().fromJson(json, VlessConfig::class.java)
            LogManager.addLog("✅ Parse: ${c.type} ${c.server}:${c.port}")
            
            xray = XrayCoreService(this)
            
            if (!xray!!.start(c)) {
                LogManager.addLog("❌ Xray falhou")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // VPN
            val builder = Builder()
                .setSession(c.remark)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpn = builder.establish()
            
            if (vpn == null) {
                LogManager.addLog("❌ VPN null")
                stopSelf()
                return START_NOT_STICKY
            }
            
            LogManager.addLog("✅ VPN estabelecida")
            running = true
            
            thread = Thread({ loop() }, "VPN").also {
                it.setUncaughtExceptionHandler { _, ex -> LogManager.addLog("❌ Thread: ${ex.message}") }
                it.start()
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ Erro: ${e.message}")
            stopSelf()
        }
        
        return START_STICKY
    }

    private fun loop() {
        try {
            LogManager.addLog("Loop VPN rodando (dokodemo)")
            val input = FileInputStream(vpn!!.fileDescriptor)
            val output = FileOutputStream(vpn!!.fileDescriptor)
            val buffer = ByteArray(32767)
            var count = 0
            
            while (running) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        count++
                        if (count % 50 == 0) LogManager.addLog("📦 $count")
                        
                        // Encaminhar para dokodemo-door
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.DOKODEMO_PORT), 5000)
                            protect(sock)
                            sock.getOutputStream().write(buffer, 0, len)
                            // Dokodemo-door não responde no mesmo socket TCP para UDP
                            // A resposta vem pelo próprio túnel VPN
                            sock.close()
                        } catch (e: Exception) {
                            // Ignora pacotes que não consegue rotear
                        }
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) {
                    if (running) LogManager.addLog("Loop: ${e.message}")
                }
            }
            LogManager.addLog("Fim: $count pacotes")
        } catch (e: Exception) {
            LogManager.addLog("❌ Loop fatal: ${e.message}")
        }
    }

    override fun onDestroy() {
        LogManager.addLog("onDestroy")
        running = false
        thread?.interrupt()
        xray?.stop()
        try { vpn?.close() } catch (e: Exception) {}
        super.onDestroy()
    }
}
