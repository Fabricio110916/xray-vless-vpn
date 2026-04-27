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
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {
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
        }

        pasteButton.setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0)
                configEditText.setText(clip.getItemAt(0).text.toString())
        }

        connectButton.setOnClickListener {
            if (isConnected) disconnectVpn()
            else {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, 100)
                else startVpn()
            }
        }

        copyLogButton.setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            clip.setPrimaryClip(ClipData.newPlainText("logs", LogManager.getLogs()))
            Toast.makeText(this, "Copiado!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processConfig(url: String) {
        try {
            if (!url.startsWith("vless://")) return
            val config = parseUrl(url)
            getSharedPreferences("vpn", MODE_PRIVATE).edit()
                .putString("config", Gson().toJson(config)).apply()
            connectButton.isEnabled = true
            Toast.makeText(this, "✅ OK", Toast.LENGTH_SHORT).show()
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
            uuid = uuid, server = hp[0], port = hp.getOrNull(1)?.toIntOrNull() ?: 443,
            encryption = params["encryption"] ?: "none",
            security = params["security"] ?: "none",
            type = params["type"] ?: "tcp",
            remark = remark, host = params["host"] ?: "",
            path = params["path"] ?: "/", sni = params["sni"] ?: hp[0],
            mode = params["mode"] ?: "auto", alpn = params["alpn"] ?: "",
            insecure = params["insecure"] == "1", allowInsecure = params["allowInsecure"] == "1"
        )
    }

    private fun startVpn() {
        val json = getSharedPreferences("vpn", MODE_PRIVATE).getString("config", null) ?: return
        val intent = Intent(this, V2RayVpnService::class.java).putExtra("vless_config", json)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBound = true
        isConnected = true
        updateStatus()
    }

    // CORREÇÃO: Método público para o sistema chamar ao desconectar
    fun disconnectVpn() {
        LogManager.addLog("Desconectando...")
        try {
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }
        } catch (e: Exception) {}
        
        stopService(Intent(this, V2RayVpnService::class.java))
        isConnected = false
        updateStatus()
        LogManager.addLog("✅ Desconectado")
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
        if (serviceBound) try { unbindService(serviceConnection) } catch (e: Exception) {}
    }
}
