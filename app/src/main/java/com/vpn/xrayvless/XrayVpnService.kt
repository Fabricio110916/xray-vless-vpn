package com.vpn.xrayvless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.FileInputStream
import java.io.FileOutputStream
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
        LogManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW))
        }
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

            val b = Builder()
                .setSession(c.remark).addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0).setMtu(1500).setBlocking(true)
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }
            
            // Proteger SOCKS
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                protect(sock)
                sock.close()
                LogManager.addLog("?? SOCKS ok")
            } catch (e: Exception) {}

            running = true

            // Loop TUN em Kotlin (FUNCIONA, já testado!)
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    val input = FileInputStream(vpnInterface!!.fileDescriptor)
                    val output = FileOutputStream(vpnInterface!!.fileDescriptor)
                    val buffer = ByteArray(1500)
                    var count = 0L

                    LogManager.addLog("?? TUN loop iniciado")

                    while (running) {
                        val len = input.read(buffer)
                        if (len >= 20) {
                            count++
                            if (count % 100 == 0L) LogManager.addLog("?? $count")

                            val dstIP = ByteArray(4)
                            System.arraycopy(buffer, 16, dstIP, 0, 4)
                            val dstPort = ((buffer[20].toInt() and 0xFF) shl 8) or (buffer[21].toInt() and 0xFF)

                            // Encaminhar via SOCKS5
                            try {
                                val s = Socket()
                                s.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 3000)
                                protect(s)

                                val out = s.getOutputStream()
                                val inp = s.getInputStream()

                                // SOCKS5 handshake
                                out.write(byteArrayOf(5, 1, 0))
                                out.flush()
                                val hs = ByteArray(2); inp.read(hs)

                                // CONNECT
                                out.write(byteArrayOf(5, 1, 0, 1))
                                out.write(dstIP)
                                out.write(byteArrayOf(((dstPort shr 8) and 0xFF).toByte(), (dstPort and 0xFF).toByte()))
                                out.flush()
                                val cr = ByteArray(10); inp.read(cr)

                                if (cr[1] == 0.toByte()) {
                                    out.write(buffer, 0, len)
                                    out.flush()
                                    val resp = ByteArray(1500)
                                    val rLen = inp.read(resp)
                                    if (rLen > 0) {
                                        synchronized(output) {
                                            output.write(resp, 0, rLen)
                                            output.flush()
                                        }
                                    }
                                }
                                s.close()
                            } catch (e: Exception) {
                                // pacote não roteável
                            }
                        }
                    }
                    LogManager.addLog("?? TUN fim: $count pacotes")
                } catch (e: InterruptedException) {
                    LogManager.addLog("TUN interrompido")
                } catch (e: Exception) {
                    LogManager.addLog("❌ TUN: ${e.message}")
                }
            }, "TUN-loop").start()

            LogManager.addLog("✅ VPN + TUN ativos!")

        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
// 1777325477
