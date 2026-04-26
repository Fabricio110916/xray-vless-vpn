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

    private val ligacao = LigacaoLocal()
    private var interfaceVpn: ParcelFileDescriptor? = null
    private var executando = false
    private val escopoServico = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    inner class LigacaoLocal : Binder() {
        fun obterServico(): XrayVpnService = this@XrayVpnService
    }

    override fun onBind(intencao: Intent?): IBinder {
        return ligacao
    }

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao()
    }

    override fun onStartCommand(intencao: Intent?, flags: Int, idInicio: Int): Int {
        val notificacao = criarNotificacao()
        startForeground(ID_NOTIFICACAO, notificacao)
        
        intencao?.getStringExtra("config_vless")?.let { configJson ->
            val configVless = Gson().fromJson(configJson, VlessConfig::class.java)
            iniciarConexaoVpn(configVless)
        }
        
        return START_STICKY
    }

    private fun iniciarConexaoVpn(config: VlessConfig) {
        escopoServico.launch {
            try {
                val construtor = Builder()
                    .setSession(config.apelido)
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)
                    
                interfaceVpn = construtor.establish()
                
                if (interfaceVpn != null) {
                    executando = true
                    executarNucleoXray(config)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
    }

    private fun executarNucleoXray(config: VlessConfig) {
        escopoServico.launch {
            try {
                while (executando) {
                    interfaceVpn?.let { descritor ->
                        val fluxoEntrada = FileInputStream(descritor.fileDescriptor)
                        val fluxoSaida = FileOutputStream(descritor.fileDescriptor)
                        
                        val buffer = ByteArray(32767)
                        var comprimento: Int
                        
                        while (fluxoEntrada.read(buffer).also { comprimento = it } != -1) {
                            fluxoSaida.write(buffer, 0, comprimento)
                        }
                    }
                    delay(100)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                ID_CANAL,
                "Status da VPN XRAY",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status da conexão VPN XRAY"
            }
            
            val gerenciadorNotificacao = getSystemService(NotificationManager::class.java)
            gerenciadorNotificacao.createNotificationChannel(canal)
        }
    }

    private fun criarNotificacao(): Notification {
        val intencaoPendente = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle("XRAY VLESS VPN")
            .setContentText("VPN Conectada")
            .setSmallIcon(R.drawable.ic_chave_vpn)
            .setContentIntent(intencaoPendente)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        executando = false
        interfaceVpn?.close()
        escopoServico.cancel()
    }

    companion object {
        private const val ID_NOTIFICACAO = 1
        private const val ID_CANAL = "canal_vpn_xray"
    }
}
