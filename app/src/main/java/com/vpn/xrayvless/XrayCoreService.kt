package com.vpn.xrayvless

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class XrayCoreService(private val context: Context) {
    
    companion object {
        private const val TAG = "XrayCoreService"
        private const val SOCKS_PORT = 10808
    }
    
    private var isRunning = false
    private var process: Process? = null
    private var job: Job? = null
    
    private val xrayPath: String by lazy {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val xrayFile = File(nativeDir, "libxray.so")
        if (xrayFile.exists()) {
            Log.d(TAG, "Xray encontrado em: ${xrayFile.absolutePath}")
            xrayFile.absolutePath
        } else {
            Log.e(TAG, "❌ libxray.so nao encontrado em ${nativeDir}")
            ""
        }
    }
    
    fun start(config: VlessConfig, socksPort: Int = SOCKS_PORT): Boolean {
        if (isRunning) {
            Log.w(TAG, "Xray ja esta rodando")
            return false
        }
        
        try {
            // Criar diretório de config
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()
            
            // Gerar config JSON
            val configJson = buildXrayConfig(config, socksPort)
            val configFile = File(configDir, "config.json")
            configFile.writeText(configJson)
            Log.d(TAG, "Config salva: ${configFile.absolutePath}")
            Log.d(TAG, "Config: $configJson")
            
            // Copiar binário xray se necessário
            val xrayBin = File(configDir, "xray")
            if (!xrayBin.exists() && xrayPath.isNotEmpty()) {
                File(xrayPath).copyTo(xrayBin)
                xrayBin.setExecutable(true)
                Log.d(TAG, "Xray copiado para: ${xrayBin.absolutePath}")
            }
            
            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (xrayBin.exists()) {
                        // Iniciar Xray como processo
                        val pb = ProcessBuilder(
                            xrayBin.absolutePath,
                            "run",
                            "-config", configFile.absolutePath
                        )
                        pb.directory(configDir)
                        pb.redirectErrorStream(true)
                        
                        process = pb.start()
                        isRunning = true
                        
                        Log.d(TAG, "✅ Xray iniciado (PID: ${process?.pid()})")
                        
                        // Ler saída
                        launch {
                            process?.inputStream?.bufferedReader()?.use { reader ->
                                reader.lines().forEach { line ->
                                    Log.d(TAG, "XRAY: $line")
                                }
                            }
                        }
                        
                        // Aguardar processo
                        val exitCode = process?.waitFor() ?: -1
                        Log.d(TAG, "Xray finalizado com código: $exitCode")
                        isRunning = false
                    } else {
                        Log.e(TAG, "❌ Binário xray não encontrado")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no Xray: ${e.message}", e)
                    isRunning = false
                }
            }
            
            // Aguardar um pouco para o Xray iniciar
            Thread.sleep(1000)
            return isRunning
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar Xray: ${e.message}", e)
            return false
        }
    }
    
    fun stop() {
        Log.d(TAG, "Parando Xray...")
        isRunning = false
        process?.destroy()
        job?.cancel()
        Log.d(TAG, "Xray parado")
    }
    
    fun isActive(): Boolean = isRunning
    
    fun getSocksPort(): Int = SOCKS_PORT
    
    private fun buildXrayConfig(config: VlessConfig, socksPort: Int): String {
        val outbounds = JSONObject()
        outbounds.put("protocol", "vless")
        outbounds.put("tag", "proxy")
        
        val vnext = JSONObject()
        vnext.put("address", config.server)
        vnext.put("port", config.port)
        
        val user = JSONObject()
        user.put("id", config.uuid)
        user.put("encryption", config.encryption)
        if (config.flow.isNotEmpty()) user.put("flow", config.flow)
        
        vnext.put("users", listOf(user))
        outbounds.put("settings", JSONObject().apply {
            put("vnext", listOf(vnext))
        })
        
        // Stream settings
        val streamSettings = JSONObject()
        streamSettings.put("network", config.type)
        streamSettings.put("security", config.security)
        
        when (config.type) {
            "ws" -> {
                val ws = JSONObject()
                ws.put("path", config.path)
                if (config.host.isNotEmpty()) {
                    ws.put("headers", JSONObject().apply {
                        put("Host", config.host)
                    })
                }
                streamSettings.put("wsSettings", ws)
            }
            "xhttp" -> {
                val xhttp = JSONObject()
                xhttp.put("mode", config.mode)
                xhttp.put("path", config.path)
                if (config.host.isNotEmpty()) xhttp.put("host", config.host)
                streamSettings.put("xhttpSettings", xhttp)
            }
            "tcp" -> {
                streamSettings.put("tcpSettings", JSONObject())
            }
        }
        
        if (config.security == "tls") {
            val tls = JSONObject()
            tls.put("serverName", config.sni)
            tls.put("allowInsecure", config.insecure)
            if (config.alpn.isNotEmpty()) {
                tls.put("alpn", config.alpn.split(","))
            }
            streamSettings.put("tlsSettings", tls)
        }
        
        outbounds.put("streamSettings", streamSettings)
        
        // Config completa
        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })
            put("inbounds", listOf(
                JSONObject().apply {
                    put("tag", "socks-in")
                    put("port", socksPort)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                }
            ))
            put("outbounds", listOf(
                outbounds,
                JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                }
            ))
            put("routing", JSONObject().apply {
                put("rules", listOf(
                    JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", listOf("socks-in"))
                        put("outboundTag", "proxy")
                    }
                ))
            })
        }.toString(2)
    }
}
