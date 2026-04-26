package com.vpn.xrayvless

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class XrayCoreService(private val context: Context) {
    
    companion object {
        private const val TAG = "XrayCoreService"
        private const val CONFIG_DIR = "xray_config"
    }
    
    private var isRunning = false
    private var job: Job? = null
    
    fun start(config: VlessConfig, socksPort: Int = 10808): Boolean {
        if (isRunning) {
            Log.w(TAG, "Xray ja esta rodando")
            return false
        }
        
        try {
            val configJson = buildXrayConfig(config, socksPort)
            Log.d(TAG, "Config Xray gerada: $configJson")
            
            // Salvar config
            val configDir = File(context.filesDir, CONFIG_DIR)
            configDir.mkdirs()
            val configFile = File(configDir, "config.json")
            configFile.writeText(configJson)
            
            Log.d(TAG, "Config salva em: ${configFile.absolutePath}")
            
            // Iniciar Xray
            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "Iniciando Xray Core...")
                    // Chamar libxray para iniciar
                    val result = startXray(configFile.absolutePath, configDir.absolutePath)
                    if (result) {
                        isRunning = true
                        Log.d(TAG, "✅ Xray Core iniciado com sucesso")
                    } else {
                        Log.e(TAG, "❌ Falha ao iniciar Xray Core")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao iniciar Xray: ${e.message}", e)
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar Xray: ${e.message}", e)
            return false
        }
    }
    
    fun stop() {
        Log.d(TAG, "Parando Xray Core...")
        isRunning = false
        job?.cancel()
        stopXray()
        Log.d(TAG, "Xray Core parado")
    }
    
    private fun buildXrayConfig(config: VlessConfig, socksPort: Int): String {
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
                JSONObject().apply {
                    put("tag", "proxy")
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", listOf(
                            JSONObject().apply {
                                put("address", config.server)
                                put("port", config.port)
                                put("users", listOf(
                                    JSONObject().apply {
                                        put("id", config.uuid)
                                        put("encryption", config.encryption)
                                        put("flow", config.flow)
                                    }
                                ))
                            }
                        ))
                    })
                    put("streamSettings", JSONObject().apply {
                        put("network", config.type)
                        put("security", config.security)
                        
                        if (config.type == "ws" || config.type == "xhttp") {
                            put("wsSettings", JSONObject().apply {
                                put("path", config.path)
                                if (config.host.isNotEmpty()) {
                                    put("headers", JSONObject().apply {
                                        put("Host", config.host)
                                    })
                                }
                            })
                        }
                        
                        if (config.security == "tls") {
                            put("tlsSettings", JSONObject().apply {
                                put("serverName", config.sni)
                                put("allowInsecure", config.insecure)
                                if (config.alpn.isNotEmpty()) {
                                    put("alpn", config.alpn.split(","))
                                }
                            })
                        }
                        
                        if (config.type == "xhttp") {
                            put("xhttpSettings", JSONObject().apply {
                                put("mode", config.mode)
                                put("path", config.path)
                                if (config.host.isNotEmpty()) {
                                    put("host", config.host)
                                }
                            })
                        }
                    })
                    put("mux", JSONObject().apply {
                        put("enabled", false)
                    })
                },
                JSONObject().apply {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    put("settings", JSONObject())
                }
            ))
            
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
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
    
    private external fun startXray(configPath: String, assetPath: String): Boolean
    private external fun stopXray()
    
    init {
        try {
            System.loadLibrary("xray")
            Log.d(TAG, "libxray carregada com sucesso")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ ERRO: libxray nao encontrada! ${e.message}")
            Log.e(TAG, "É necessario compilar o Xray Core para Android primeiro")
        }
    }
}
