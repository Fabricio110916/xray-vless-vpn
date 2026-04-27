package com.vpn.xrayvless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.*
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.net.Socket

class XrayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var xray: XrayCoreService? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            startForeground(1, NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

            val c = Gson().fromJson(i?.getStringExtra("vless_config"), VlessConfig::class.java)

            // Iniciar Xray
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { stopSelf(); return START_NOT_STICKY }

            // Criar VPN
            vpnInterface = Builder()
                .setSession(c.remark)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
                .also { it.addDisallowedApplication(packageName) }
                .establish()

            if (vpnInterface == null) { stopSelf(); return START_NOT_STICKY }
            LogManager.addLog("✅ VPN OK")
            
            // Proteger SOCKS (padrão v2rayNG)
            protectSocket(127, 0, 0, 1, XrayCoreService.SOCKS_PORT)

            running = true

            // Loop TUN simples
            Thread({
                try {
                    val fd = vpnInterface!!.fileDescriptor
                    val inp = ParcelFileDescriptor.AutoCloseInputStream(vpnInterface)
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(vpnInterface)
                    val buf = ByteArray(1500)
                    var n = 0L

                    while (running) {
                        val len = inp.read(buf)
                        if (len >= 20) {
                            n++
                            if (n % 100 == 0L) LogManager.addLog("?? $n")
                            
                            val dstIP = buf.copyOfRange(16, 20)
                            val dstPort = ((buf[20].toInt() and 0xFF) shl 8) or (buf[21].toInt() and 0xFF)
                            
                            try {
                                val s = Socket()
                                s.soTimeout = 10000
                                s.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 3000)
                                protect(s)
                                
                                val si = s.getInputStream()
                                val so = s.getOutputStream()
                                
                                // SOCKS5
                                so.write(byteArrayOf(5, 1, 0))
                                so.flush(); si.read(ByteArray(2))
                                
                                // CONNECT
                                so.write(byteArrayOf(5, 1, 0, 1, dstIP[0], dstIP[1], dstIP[2], dstIP[3], ((dstPort shr 8) and 0xFF).toByte(), (dstPort and 0xFF).toByte()))
                                so.flush(); si.read(ByteArray(10))
                                
                                // Forward
                                so.write(buf, 0, len); so.flush()
                                val r = ByteArray(1500)
                                val rl = si.read(r)
                                if (rl > 0) { out.write(r, 0, rl); out.flush() }
                                
                                s.close()
                            } catch (e: Exception) {}
                        }
                    }
                    LogManager.addLog("TUN fim: $n")
                } catch (e: InterruptedException) {} 
                catch (e: Exception) { LogManager.addLog("TUN: ${e.message}") }
            }, "TUN").start()
            
            LogManager.addLog("✅ Ativo!")
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun protectSocket(a: Int, b: Int, c: Int, d: Int, port: Int) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress("$a.$b.$c.$d", port), 3000)
            protect(s)
            s.close()
            LogManager.addLog("?? $a.$b.$c.$d:$port")
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        running = false
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = object : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }
}
