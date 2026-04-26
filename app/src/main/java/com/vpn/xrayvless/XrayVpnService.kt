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
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream

class XrayVpnService : VpnService() {

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        intent?.getStringExtra("vless_config")?.let { configJson ->
            val vlessConfig = Gson().fromJson(configJson, VlessConfig::class.java)
            startVpnConnection(vlessConfig)
        }
        
        return START_STICKY
    }

    private fun startVpnConnection(config: VlessConfig) {
        serviceScope.launch {
            try {
                // Configurar interface VPN
                val builder = Builder()
                    .setSession(config.remark)
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)
                    
                vpnInterface = builder.establish()
                
                if (vpnInterface != null) {
                    isRunning = true
                    // Aqui você implementaria a lógica de proxy X-ray
                    runXrayCore(config)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
    }

    private fun runXrayCore(config: VlessConfig) {
        // Implementação do X-ray Core
        // Esta é uma simplificação - você precisará integrar a biblioteca X-ray real
        serviceScope.launch {
            try {
                while (isRunning) {
                    // Processar pacotes VPN através do X-ray
                    vpnInterface?.let { pfd ->
                        val inputStream = FileInputStream(pfd.fileDescriptor)
                        val outputStream = FileOutputStream(pfd.fileDescriptor)
                        
                        val buffer = ByteArray(32767)
                        var length: Int
                        
                        while (inputStream.read(buffer).also { length = it } != -1) {
                            // Encaminhar dados através do proxy VLESS
                            outputStream.write(buffer, 0, length)
                        }
                    }
                    delay(100)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "XRAY VPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status da conexão VPN XRAY"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XRAY VLESS VPN")
            .setContentText("VPN Conectada")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        vpnInterface?.close()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "xray_vpn_channel"
    }
}
