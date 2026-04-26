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
import java.nio.ByteBuffer

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpnFd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var vpnThread: Thread? = null
    private var xray: XrayCoreService? = null
    private val handler = Handler(Looper.getMainLooper())

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
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpnFd = builder.establish()
            if (vpnFd == null) { stopSelf(); return START_NOT_STICKY }
            
            // Proteger socket SOCKS
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 3000)
                protect(s)
                s.close()
            } catch (e: Exception) {}
            
            running = true
            
            // Usar HandlerThread para o loop VPN
            val thread = android.os.HandlerThread("VPN-Tunnel")
            thread.start()
            val tunnelHandler = Handler(thread.looper)
            
            tunnelHandler.post {
                runTunnel(vpnFd!!.fileDescriptor)
            }
            
            LogManager.addLog("✅ Túnel ativo")
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun runTunnel(fd: FileDescriptor) {
        try {
            val inputStream = java.io.FileInputStream(vpnFd?.fileDescriptor)
            val outputStream = java.io.FileOutputStream(vpnFd?.fileDescriptor)
            val buffer = ByteArray(32767)
            var count = 0
            LogManager.addLog("Loop iniciado")
            while (running) {
                try {
                    val len = inputStream.read(buffer)
                    if (len > 0) {
                        count++
                        if (count % 50 == 0) LogManager.addLog("?? $count")
                        forwardToSocks(buffer, len, outputStream)
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) { if (running && count < 5) LogManager.addLog("Err: ${e.message}") }
            }
            LogManager.addLog("Fim: $count pacotes")
        } catch (e: Exception) { LogManager.addLog("❌ Fatal: ${e.message}") }
    }

    private fun forwardToSocks(data: ByteArray, len: Int, out: java.io.FileOutputStream) {
        try {
            if (len < 20) return
            
            val version = (data[0].toInt() shr 4) and 0x0F
            val protocol = data[9].toInt() and 0xFF
            
            if (version != 4 || (protocol != 6 && protocol != 17)) return
            
            val dstIP = data.copyOfRange(16, 20)
            val dstPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
            
            // Só processar pacotes TCP SYN (nova conexão)
            val headerLen = (data[0].toInt() and 0x0F) * 4
            if (protocol == 6 && headerLen + 13 < len) {
                val flags = data[headerLen + 13].toInt() and 0xFF
                if ((flags and 0x02) == 0) return // Não é SYN
            }
            
            // Conectar ao SOCKS5
            val sock = Socket()
            sock.soTimeout = 10000
            sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
            protect(sock)
            
            val sockIn = sock.getInputStream()
            val sockOut = sock.getOutputStream()
            
            // Handshake SOCKS5
            sockOut.write(byteArrayOf(5, 1, 0))
            val resp = ByteArray(2)
            sockIn.read(resp)
            
            if (resp[1] != 0.toByte()) { sock.close(); return }
            
            // SOCKS5 CONNECT
            val cmd = if (protocol == 6) 1.toByte() else 3.toByte()
            sockOut.write(byteArrayOf(5, cmd, 0, 1))
            sockOut.write(dstIP)
            sockOut.write(shortToBytes(dstPort))
            sockOut.flush()
            
            val connResp = ByteArray(10)
            sockIn.read(connResp)
            
            if (connResp[1] != 0.toByte()) { sock.close(); return }
            
            // Enviar dados
            sockOut.write(data, 0, len)
            sockOut.flush()
            
            // Ler resposta
            val response = ByteArray(32767)
            val rLen = sockIn.read(response)
            if (rLen > 0) {
                synchronized(out) {
                    out.write(response, 0, rLen)
                    out.flush()
                }
            }
            
            sock.close()
        } catch (e: Exception) {
            // Normal - timeout ou conexão recusada
        }
    }

    private fun shortToBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    override fun onDestroy() {
        LogManager.addLog("Destroy VPN")
        running = false
        
        // Parar Xray
        xray?.stop()
        
        // Fechar VPN
        try {
            vpnFd?.close()
        } catch (e: Exception) {
            LogManager.addLog("Close err: ${e.message}")
        }
        
        // Remover notificação
        stopForeground(true)
        
        LogManager.addLog("VPN destruída")
        super.onDestroy()
    }
}
