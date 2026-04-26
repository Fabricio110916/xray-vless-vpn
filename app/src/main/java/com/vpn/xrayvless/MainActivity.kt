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
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            LogManager.addLog("Serviço conectado")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            LogManager.addLog("Serviço desconectado")
            isConnected = false
            serviceBound = false
            runOnUiThread { updateStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LogManager.init(this)

        configEditText = findViewById(R.id.configEditText)
        importButton = findViewById(R.id.importButton)
        pasteButton = findViewById(R.id.pasteButton)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        logTextView = findViewById(R.id.logTextView)
        copyLogButton = findViewById(R.id.copyLogButton)

        LogManager.addListener { line ->
            runOnUiThread {
                logTextView.append("\n$line")
                (logTextView.parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
        logTextView.text = LogManager.getLogs()

        importButton.setOnClickListener {
            val t = configEditText.text.toString().trim()
            if (t.isNotEmpty()) processConfig(t)
            else Toast.makeText(this, "Cole a config!", Toast.LENGTH_SHORT).show()
        }

        pasteButton.setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) {
                configEditText.setText(clip.getItemAt(0).text.toString())
                LogManager.addLog("Config colada")
            }
        }

        connectButton.setOnClickListener {
            if (isConnected) {
                disconnectVpn()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, 100)
                else startVpn()
            }
        }

        copyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("logs", LogManager.getLogs()))
            Toast.makeText(this, "Logs copiados!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processConfig(url: String) {
        try {
            if (!url.startsWith("vless://")) return
            val config = parseUrl(url)
            getSharedPreferences("vpn", MODE_PRIVATE).edit()
                .putString("config", Gson().toJson(config)).apply()
            LogManager.addLog("✅ ${config.type} ${config.server}:${config.port}")
            connectButton.isEnabled = true
            Toast.makeText(this, "✅ ${config.server}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
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
        val remark = if (h > 0) try { URLDecoder.decode(rest.substring(h + 1), "UTF-8") } catch (e: Exception) { rest.substring(h + 1) } else "VPN"
        return VlessConfig(
            uuid = uuid, server = server, port = port,
            encryption = params["encryption"] ?: "none",
            security = params["security"] ?: "none",
            type = params["type"] ?: "tcp",
            remark = remark, host = params["host"] ?: "",
            path = params["path"] ?: "/", sni = params["sni"] ?: server,
            mode = params["mode"] ?: "auto", alpn = params["alpn"] ?: "",
            insecure = params["insecure"] == "1", allowInsecure = params["allowInsecure"] == "1"
        )
    }

    private fun startVpn() {
        val json = getSharedPreferences("vpn", MODE_PRIVATE).getString("config", null)
        if (json == null) { Toast.makeText(this, "Importe config!", Toast.LENGTH_SHORT).show(); return }
        try {
            val intent = Intent(this, XrayVpnService::class.java).putExtra("vless_config", json)
            ContextCompat.startForegroundService(this, intent)
            
            // Vincular ao serviço
            if (!serviceBound) {
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                serviceBound = true
            }
            
            isConnected = true
            updateStatus()
            LogManager.addLog("VPN iniciada")
        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
        }
    }

    private fun disconnectVpn() {
        LogManager.addLog("Desconectando VPN...")
        try {
            // Desvincular serviço
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }
            
            // Parar serviço
            val intent = Intent(this, XrayVpnService::class.java)
            stopService(intent)
            
            LogManager.addLog("✅ VPN desconectada")
        } catch (e: Exception) {
            LogManager.addLog("Erro ao desconectar: ${e.message}")
        }
        
        isConnected = false
        updateStatus()
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
        if (rq == 100 && rc == Activity.RESULT_OK) startVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) {}
            serviceBound = false
        }
    }
}
