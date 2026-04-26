package com.vpn.xrayvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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
        LogManager.addLog("XrayVpnService criado")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        xray = XrayCoreService(this)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startForeground(1, NotificationCompat.Builder(this, "x")
            .setContentTitle("XRAY VPN").setContentText("Conectando...")
            .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build())

        i?.getStringExtra("vless_config")?.let { json ->
            try {
                LogManager.addLog("Carregando config...")
                val c = Gson().fromJson(json, VlessConfig::class.java)
                LogManager.addLog("Config: ${c.type} | ${c.server}:${c.port}")
                
                LogManager.addLog("Iniciando Xray Core...")
                if (xray?.start(c) == true) {
                    LogManager.addLog("✅ Xray Core rodando na porta ${XrayCoreService.SOCKS_PORT}")
                    
                    LogManager.addLog("Estabelecendo interface VPN...")
                    val builder = Builder()
                        .setSession(c.remark)
                        .addAddress("10.0.0.2", 24)
                        .addDnsServer("8.8.8.8")
                        .addDnsServer("1.1.1.1")
                        .addRoute("0.0.0.0", 0)
                        .setMtu(1500)
                        .setBlocking(true)
                    
                    // EXCLUIR O PRÓPRIO APP DA VPN (IMPORTANTE!)
                    try {
                        builder.addDisallowedApplication(packageName)
                        LogManager.addLog("✅ App excluído da VPN: $packageName")
                    } catch (e: Exception) {
                        LogManager.addLog("⚠️ Não foi possível excluir app: ${e.message}")
                    }
                    
                    vpn = builder.establish()
                    
                    if (vpn != null) {
                        running = true
                        LogManager.addLog("✅ VPN estabelecida com sucesso")
                        
                        // Proteger socket SOCKS
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 3000)
                            protect(sock)
                            sock.close()
                            LogManager.addLog("✅ Socket SOCKS protegido")
                        } catch (e: Exception) {
                            LogManager.addLog("⚠️ Aviso ao proteger SOCKS: ${e.message}")
                        }
                        
                        thread = Thread({ loop() }, "VPN").also { it.start() }
                        LogManager.addLog("✅ Thread VPN iniciada")
                    } else {
                        LogManager.addLog("❌ establish() retornou null!")
                        stopSelf()
                    }
                } else {
                    LogManager.addLog("❌ Xray Core falhou ao iniciar")
                    stopSelf()
                }
            } catch (e: Exception) {
                LogManager.addLog("❌ ERRO: ${e.message}")
                e.printStackTrace()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun loop() {
        LogManager.addLog("Loop VPN iniciado")
        try {
            val input = FileInputStream(vpn!!.fileDescriptor)
            val output = FileOutputStream(vpn!!.fileDescriptor)
            val buffer = ByteArray(32767)
            var count = 0
            var errors = 0
            
            while (running) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        count++
                        if (count % 100 == 0) {
                            LogManager.addLog("📦 $count pacotes (erros: $errors)")
                        }
                        
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                            protect(sock)
                            sock.getOutputStream().write(buffer, 0, len)
                            
                            val resp = ByteArray(32767)
                            val rLen = sock.getInputStream().read(resp)
                            if (rLen > 0) output.write(resp, 0, rLen)
                            
                            sock.close()
                        } catch (e: Exception) {
                            errors++
                            if (errors <= 3) {
                                LogManager.addLog("⚠️ Erro socket #$errors: ${e.message}")
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    LogManager.addLog("Thread VPN interrompida")
                    break
                } catch (e: Exception) {
                    if (running) {
                        errors++
                        LogManager.addLog("❌ Erro loop: ${e.message}")
                        if (errors > 10) {
                            LogManager.addLog("❌ Muitos erros, parando...")
                            break
                        }
                    }
                }
            }
            LogManager.addLog("Loop finalizado. Total: $count pacotes, $errors erros")
        } catch (e: Exception) {
            LogManager.addLog("❌ ERRO FATAL: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        LogManager.addLog("Parando serviço VPN...")
        running = false
        thread?.interrupt()
        xray?.stop()
        try { vpn?.close() } catch (e: Exception) {}
        LogManager.addLog("Serviço VPN parado")
        super.onDestroy()
    }
}
