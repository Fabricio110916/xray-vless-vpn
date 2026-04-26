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
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

class XrayVpnService : VpnService() {

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var vpnThread: Thread? = null
    private var xrayCore: XrayCoreService? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "XrayVpnService criado")
        createNotificationChannel()
        xrayCore = XrayCoreService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        intent?.getStringExtra("vless_config")?.let { configJson ->
            try {
                val config = Gson().fromJson(configJson, VlessConfig::class.java)
                Log.d(TAG, "Config carregada - Tipo: ${config.type}")
                
                // Iniciar Xray Core
                val xrayStarted = xrayCore?.start(config) ?: false
                if (xrayStarted) {
                    Log.d(TAG, "✅ Xray Core iniciado, iniciando VPN...")
                    startVpnConnection(config)
                } else {
                    Log.e(TAG, "❌ Falha ao iniciar Xray Core")
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
                Log.d(TAG, "VPN estabelecida")
                
                vpnThread = Thread({
                    protectSocket()
                    processPackets(config)
                }, "VPN-Thread")
                
                vpnThread?.start()
                updateNotification("Conectado: ${config.server} | ${config.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao estabelecer VPN: ${e.message}", e)
            stopSelf()
        }
    }

    private fun protectSocket() {
        try {
            // Proteger socket do Xray para evitar loop
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", 10808), 1000)
            protect(socket)
            socket.close()
            Log.d(TAG, "Socket SOCKS protegido")
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao proteger socket: ${e.message}")
        }
    }

    private fun processPackets(config: VlessConfig) {
        try {
            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(32767)
            var packetCount = 0
            
            Log.d(TAG, "Processando pacotes...")
            
            while (isRunning) {
                try {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        packetCount++
                        if (packetCount % 50 == 0) {
                            Log.d(TAG, "?? $packetCount pacotes processados")
                        }
                        
                        // Encaminhar para o proxy SOCKS do Xray
                        try {
                            val proxySocket = Socket()
                            proxySocket.connect(InetSocketAddress("127.0.0.1", 10808), 5000)
                            protect(proxySocket)
                            
                            proxySocket.getOutputStream().write(buffer, 0, length)
                            
                            val response = ByteArray(32767)
                            val responseLength = proxySocket.getInputStream().read(response)
                            if (responseLength > 0) {
                                outputStream.write(response, 0, responseLength)
                            }
                            
                            proxySocket.close()
                        } catch (e: Exception) {
                            Log.v(TAG, "Pacote descartado: ${e.message}")
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Erro: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "Total de pacotes: $packetCount")
        } catch (e: Exception) {
            Log.e(TAG, "Erro fatal: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "XRAY VPN", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Status VPN XRAY" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XRAY VLESS VPN")
            .setContentText("Conectando...")
            .setSmallIcon(R.drawable.ic_chave_vpn)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XRAY VLESS VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_chave_vpn)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "Parando servico...")
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
