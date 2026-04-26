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

class MainActivity : AppCompatActivity() {

    private lateinit var configEditText: TextInputEditText
    private lateinit var importButton: Button
    private lateinit var pasteButton: Button
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: android.view.View
    private lateinit var trafficText: TextView
    
    private var isConnected = false
    private var vpnService: XrayVpnService? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as XrayVpnService.LocalBinder
            vpnService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        bindVpnService()
    }

    private fun initViews() {
        configEditText = findViewById(R.id.configEditText)
        importButton = findViewById(R.id.importButton)
        pasteButton = findViewById(R.id.pasteButton)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        trafficText = findViewById(R.id.trafficText)
    }

    private fun setupListeners() {
        importButton.setOnClickListener {
            val configText = configEditText.text.toString().trim()
            if (configText.isNotEmpty()) {
                processVlessConfig(configText)
            } else {
                Toast.makeText(this, "Cole uma configuração VLESS primeiro!", Toast.LENGTH_SHORT).show()
            }
        }

        pasteButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pastedText = clip.getItemAt(0).text.toString()
                configEditText.setText(pastedText)
                Toast.makeText(this, "Configuração colada! Clique em Importar", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nada na área de transferência!", Toast.LENGTH_SHORT).show()
            }
        }

        connectButton.setOnClickListener {
            if (isConnected) {
                disconnectVpn()
            } else {
                connectVpn()
            }
        }
    }

    private fun processVlessConfig(configUrl: String) {
        try {
            if (configUrl.startsWith("vless://")) {
                val vlessConfig = parseVlessUrl(configUrl)
                saveConfigToPreferences(vlessConfig)
                Toast.makeText(this, "Configuração VLESS importada com sucesso!", Toast.LENGTH_LONG).show()
                updateUIAfterImport()
            } else {
                Toast.makeText(this, "URL VLESS inválida!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseVlessUrl(url: String): VlessConfig {
        val cleanUrl = url.removePrefix("vless://")
        val parts = cleanUrl.split("@")
        
        val uuid = parts[0]
        val serverAndParams = parts[1].split("?")
        val serverAndPort = serverAndParams[0].split(":")
        val server = serverAndPort[0]
        val port = serverAndPort[1].toInt()
        
        val params = mutableMapOf<String, String>()
        var remark = "XRAY VLESS VPN"
        
        if (serverAndParams.size > 1) {
            val paramAndFragment = serverAndParams[1].split("#")
            if (paramAndFragment.size > 1) {
                remark = paramAndFragment[1]
            }
            paramAndFragment[0].split("&").forEach { param ->
                val keyValue = param.split("=")
                if (keyValue.size == 2) {
                    params[keyValue[0]] = keyValue[1]
                }
            }
        }
        
        return VlessConfig(
            uuid = uuid,
            server = server,
            port = port,
            encryption = params["encryption"] ?: "none",
            security = params["security"] ?: "none",
            type = params["type"] ?: "tcp",
            flow = params["flow"] ?: "",
            remark = remark
        )
    }

    private fun saveConfigToPreferences(config: VlessConfig) {
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        with(prefs.edit()) {
            putString("vless_config", Gson().toJson(config))
            apply()
        }
    }

    private fun updateUIAfterImport() {
        connectButton.isEnabled = true
        connectButton.text = "CONECTAR"
    }

    private fun connectVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    private fun disconnectVpn() {
        stopService(Intent(this, XrayVpnService::class.java))
        isConnected = false
        updateConnectionStatus()
    }

    private fun startVpnService() {
        val prefs = getSharedPreferences("vpn_config", MODE_PRIVATE)
        val configJson = prefs.getString("vless_config", null)
        
        if (configJson == null) {
            Toast.makeText(this, "Importe uma configuração VLESS primeiro!", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, XrayVpnService::class.java).apply {
            putExtra("vless_config", configJson)
        }
        
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        isConnected = true
        updateConnectionStatus()
    }

    private fun bindVpnService() {
        val intent = Intent(this, XrayVpnService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateConnectionStatus() {
        if (isConnected) {
            statusText.text = "Conectado"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.conectado))
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.conectado))
            connectButton.text = "DESCONECTAR"
            connectButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.desconectar)
            trafficText.text = "Conectado"
        } else {
            statusText.text = "Desconectado"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.desconectado))
            statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.desconectado))
            connectButton.text = "CONECTAR"
            connectButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.conectar)
            trafficText.text = "0 KB/s"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, "Permissão VPN negada!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }

    companion object {
        private const val VPN_REQUEST_CODE = 100
    }
}
