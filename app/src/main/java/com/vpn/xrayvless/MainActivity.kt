package com.vpn.xrayvless

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var configEditText: TextInputEditText
    private lateinit var importButton: Button
    private lateinit var pasteButton: Button
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: android.view.View
    private lateinit var logTextView: TextView
    private lateinit var copyLogButton: Button

    private var isConnected = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {
            isConnected = false
            runOnUiThread { updateStatus() }
            LogManager.addLog("⚠️ Serviço VPN desconectado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        LogManager.init(this)
        LogManager.addLog("MainActivity criada")

        configEditText = findViewById(R.id.configEditText)
        importButton = findViewById(R.id.importButton)
        pasteButton = findViewById(R.id.pasteButton)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        logTextView = findViewById(R.id.logTextView)
        copyLogButton = findViewById(R.id.copyLogButton)

        // Atualizar logs na tela em tempo real
        LogManager.addListener { line ->
            runOnUiThread {
                logTextView.append("\n$line")
                // Scroll para o final
                logTextView.post {
                    (logTextView.parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }

        // Mostrar logs existentes
        logTextView.text = LogManager.getLogs()

        importButton.setOnClickListener {
            val t = configEditText.text.toString().trim()
            if (t.isNotEmpty()) {
                LogManager.addLog("Botão IMPORTAR clicado")
                processConfig(t)
            } else {
                Toast.makeText(this, "Cole a config primeiro!", Toast.LENGTH_SHORT).show()
            }
        }

        pasteButton.setOnClickListener {
            LogManager.addLog("Botão COLAR clicado")
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) {
                val texto = clip.getItemAt(0).text.toString()
                configEditText.setText(texto)
                LogManager.addLog("Config colada: ${texto.take(40)}...")
                Toast.makeText(this, "Config colada!", Toast.LENGTH_SHORT).show()
            }
        }

        connectButton.setOnClickListener {
            if (isConnected) {
                LogManager.addLog("Desconectando VPN...")
                stopService(Intent(this, XrayVpnService::class.java))
                isConnected = false
                updateStatus()
            } else {
                LogManager.addLog("Iniciando conexão VPN...")
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    LogManager.addLog("Solicitando permissão VPN...")
                    startActivityForResult(intent, 100)
                } else {
                    LogManager.addLog("Permissão VPN já concedida")
                    startVpn()
                }
            }
        }

        copyLogButton.setOnClickListener {
            val logs = LogManager.getLogs()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("logs", logs))
            Toast.makeText(this, "Logs copiados!", Toast.LENGTH_SHORT).show()
            LogManager.addLog("📋 Logs copiados para área de transferência")
        }
    }

    private fun processConfig(url: String) {
        try {
            LogManager.addLog("Processando URL: ${url.take(60)}...")
            
            if (!url.startsWith("vless://")) {
                LogManager.addLog("❌ URL inválida")
                Toast.makeText(this, "URL inválida!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val config = parseUrl(url)
            LogManager.addLog("✅ Parse OK - Tipo: ${config.type}")
            LogManager.addLog("   Server: ${config.server}:${config.port}")
            LogManager.addLog("   Host: ${config.host}")
            LogManager.addLog("   Path: ${config.path}")
            LogManager.addLog("   SNI: ${config.sni}")
            LogManager.addLog("   Security: ${config.security}")
            LogManager.addLog("   Mode: ${config.mode}")
            
            val json = Gson().toJson(config)
            getSharedPreferences("vpn", MODE_PRIVATE).edit().putString("config", json).apply()
            LogManager.addLog("💾 Config salva")
            
            Toast.makeText(this, "✅ ${config.type} | ${config.server}", Toast.LENGTH_LONG).show()
            connectButton.isEnabled = true
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ERRO NO PARSE: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseUrl(url: String): VlessConfig {
        val u = url.removePrefix("vless://")
        val at = u.indexOf("@")
        val uuid = u.substring(0, at)
        val rest = u.substring(at + 1)
        val q = rest.indexOf("?")
        val h = rest.indexOf("#")
        val hp = rest.substring(0, if (q > 0) q else rest.length).split(":")
        val server = hp[0]
        val port = hp.getOrNull(1)?.toIntOrNull() ?: 443
        
        val params = mutableMapOf<String, String>()
        if (q > 0) {
            val end = if (h > q) h else rest.length
            rest.substring(q + 1, end).split("&").forEach { p ->
                val kv = p.split("=", limit = 2)
                if (kv.size == 2) params[kv[0]] = try { URLDecoder.decode(kv[1], "UTF-8") } catch (e: Exception) { kv[1] }
            }
        }
        val remark = if (h > 0 && h < rest.length - 1) try { URLDecoder.decode(rest.substring(h + 1), "UTF-8") } catch (e: Exception) { rest.substring(h + 1) } else "VPN"

        return VlessConfig(
            uuid = uuid, server = server, port = port,
            encryption = params["encryption"] ?: "none",
            security = params["security"] ?: "none",
            type = params["type"] ?: "tcp",
            remark = remark,
            host = params["host"] ?: "",
            path = params["path"] ?: "/",
            sni = params["sni"] ?: server,
            mode = params["mode"] ?: "auto",
            alpn = params["alpn"] ?: "",
            insecure = params["insecure"] == "1",
            allowInsecure = params["allowInsecure"] == "1"
        )
    }

    private fun startVpn() {
        val json = getSharedPreferences("vpn", MODE_PRIVATE).getString("config", null)
        if (json == null) {
            LogManager.addLog("❌ Nenhuma config salva!")
            Toast.makeText(this, "Importe config!", Toast.LENGTH_SHORT).show()
            return
        }
        
        LogManager.addLog("🚀 Iniciando serviço VPN...")
        try {
            val intent = Intent(this, XrayVpnService::class.java).apply {
                putExtra("vless_config", json)
            }
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isConnected = true
            updateStatus()
            LogManager.addLog("✅ Serviço VPN iniciado")
        } catch (e: Exception) {
            LogManager.addLog("❌ ERRO ao iniciar VPN: ${e.message}")
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        runOnUiThread {
            if (isConnected) {
                statusText.text = "Conectado"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.conectado))
                statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.conectado))
                connectButton.text = "DESCONECTAR"
            } else {
                statusText.text = "Desconectado"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.desconectado))
                statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.desconectado))
                connectButton.text = "CONECTAR"
            }
        }
    }

    override fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        super.onActivityResult(rq, rc, data)
        if (rq == 100) {
            if (rc == Activity.RESULT_OK) {
                LogManager.addLog("✅ Permissão VPN concedida")
                startVpn()
            } else {
                LogManager.addLog("❌ Permissão VPN negada")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(serviceConnection) } catch (e: Exception) {}
    }
}
