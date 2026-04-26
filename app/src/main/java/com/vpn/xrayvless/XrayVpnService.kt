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
        try {
            LogManager.init(this)
            LogManager.addLog("XrayVpnService onCreate")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Exception) {
            LogManager.addLog("❌ Erro onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            LogManager.addLog(">>> onStartCommand")
            startForeground(1, NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build())

            val json = i?.getStringExtra("vless_config")
            if (json == null) {
                LogManager.addLog("❌ Nenhuma config no intent")
                stopSelf()
                return START_NOT_STICKY
            }
            
            LogManager.addLog("JSON: ${json.take(80)}...")
            
            // Parse config
            val c: VlessConfig
            try {
                c = Gson().fromJson(json, VlessConfig::class.java)
                LogManager.addLog("✅ Parse OK: ${c.type} ${c.server}:${c.port}")
            } catch (e: Exception) {
                LogManager.addLog("❌ Erro Gson: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Criar Xray
            try {
                LogManager.addLog("Criando XrayCoreService...")
                xray = XrayCoreService(this)
                LogManager.addLog("✅ XrayCoreService criado")
            } catch (e: Exception) {
                LogManager.addLog("❌ Erro criar Xray: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Iniciar Xray
            try {
                LogManager.addLog("Iniciando Xray...")
                val ok = xray!!.start(c)
                LogManager.addLog("Xray.start() = $ok")
                if (!ok) {
                    LogManager.addLog("❌ Xray não iniciou")
                    stopSelf()
                    return START_NOT_STICKY
                }
            } catch (e: Exception) {
                LogManager.addLog("❌ Erro Xray.start: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Criar VPN
            try {
                LogManager.addLog("Criando VPN builder...")
                val builder = Builder()
                    .setSession(c.remark)
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setMtu(1500)
                    .setBlocking(true)
                
                try {
                    builder.addDisallowedApplication(packageName)
                    LogManager.addLog("✅ App excluído da VPN")
                } catch (e: Exception) {
                    LogManager.addLog("⚠️ Não excluiu app: ${e.message}")
                }
                
                LogManager.addLog("Chamando establish()...")
                vpn = builder.establish()
                
                if (vpn == null) {
                    LogManager.addLog("❌ establish retornou null")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                LogManager.addLog("✅ VPN estabelecida")
                running = true
                
                // Proteger socket SOCKS
                try {
                    val sock = Socket()
                    sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 3000)
                    protect(sock)
                    sock.close()
                    LogManager.addLog("✅ SOCKS protegido")
                } catch (e: Exception) {
                    LogManager.addLog("⚠️ SOCKS: ${e.message}")
                }
                
                // Iniciar loop
                thread = Thread({ loop() }, "VPN").also {
                    it.setUncaughtExceptionHandler { _, ex ->
                        LogManager.addLog("❌ Thread crash: ${ex.message}")
                    }
                    it.start()
                }
                LogManager.addLog("✅ Loop iniciado")
                
            } catch (e: Exception) {
                LogManager.addLog("❌ Erro VPN: ${e.message}")
                LogManager.addLog("Stack: ${e.stackTraceToString().take(150)}")
                stopSelf()
                return START_NOT_STICKY
            }
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ERRO GLOBAL: ${e.message}")
            stopSelf()
        }
        
        return START_STICKY
    }

    private fun loop() {
        try {
            LogManager.addLog("Loop começou")
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
                        try {
                            val sock = Socket()
                            sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                            protect(sock)
                            sock.getOutputStream().write(buffer, 0, len)
                            sock.close()
                        } catch (e: Exception) {
                            // ignora pacotes que não consegue rotear
                        }
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) {
                    if (running) LogManager.addLog("Loop err: ${e.message}")
                }
            }
            LogManager.addLog("Loop fim: $count pacotes")
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
