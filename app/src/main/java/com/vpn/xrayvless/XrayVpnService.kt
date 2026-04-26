package com.vpn.xrayvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class XrayVpnService : VpnService() {

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var vpnThread: Thread? = null
    private var xrayCore: XrayCoreService? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servico criado")
        createNotificationChannel()
        xrayCore = XrayCoreService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        intent?.getStringExtra("vless_config")?.let { configJson ->
            try {
                val config = Gson().fromJson(configJson, VlessConfig::class.java)
                Log.d(TAG, "Tipo: ${config.type} | Server: ${config.server}")
                
                if (xrayCore?.start(config) == true) {
                    Log.d(TAG, "✅ Xray iniciado")
                    startVpnConnection(config)
                } else {
                    Log.e(TAG, "❌ Xray falhou")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro: ${e.message}", e)
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    private fun startVpnConnection(config: VlessConfig) {
        try {
            val builder = Builder()
                .setSession("XRAY ${config.remark}")
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
                
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                isRunning = true
                Log.d(TAG, "✅ VPN estabelecida")
                
                vpnThread = Thread({
                    runVpnLoop(config)
                }, "VPN-Loop")
                vpnThread?.start()
                
                updateNotification("Conectado | ${config.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro VPN: ${e.message}", e)
            stopSelf()
        }
    }

    private fun runVpnLoop(config: VlessConfig) {
        try {
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val output = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(32767)
            var count = 0
            
            while (isRunning) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        count++
                        if (count % 100 == 0) {
                            Log.d(TAG, "?? $count pacotes")
                        }
                        
                        // Encaminhar via SOCKS do Xray
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress("127.0.0.1", 10808), 3000)
                            protect(sock)
                            sock.getOutputStream().write(buffer, 0, len)
                            
                            val resp = ByteArray(32767)
                            val rLen = sock.getInputStream().read(resp)
                            if (rLen > 0) output.write(resp, 0, rLen)
                            
                            sock.close()
                        } catch (e: Exception) {
                            // Pacote não roteável, descarta
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Loop: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "Total: $count pacotes")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "XRAY VPN", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Status VPN" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("XRAY VLESS VPN")
        .setContentText("Conectando...")
        .setSmallIcon(R.drawable.ic_chave_vpn)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XRAY VLESS VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_chave_vpn)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    override fun onDestroy() {
        isRunning = false
        vpnThread?.interrupt()
        xrayCore?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XrayVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "xray_vpn"
    }
}
