package com.vpn.xrayvless

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class XrayCoreService(private val context: Context) {

    companion object {
        const val SOCKS_PORT = 10808
    }

    private var isRunning = false
    private var job: Job? = null
    private var process: Process? = null

    fun start(config: VlessConfig): Boolean {
        if (isRunning) return false

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            // Salvar config
            val json = buildConfigJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("Config salva em: ${configFile.absolutePath}")

            // Copiar libxray.so para diretório com permissão
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val xraySo = File(nativeDir, "libxray.so")
            val xrayBin = File(configDir, "xray")

            if (!xrayBin.exists() && xraySo.exists()) {
                LogManager.addLog("Copiando libxray.so -> xray...")
                xraySo.copyTo(xrayBin, overwrite = true)
                
                // CORREÇÃO: Garantir permissão de execução
                val success = xrayBin.setExecutable(true, false)  // owner only
                LogManager.addLog("setExecutable = $success")
                xrayBin.setReadable(true)
                xrayBin.setWritable(false)
                
                LogManager.addLog("Permissões: r=${xrayBin.canRead()} w=${xrayBin.canWrite()} x=${xrayBin.canExecute()}")
                LogManager.addLog("Tamanho: ${xrayBin.length()} bytes")
            }

            if (!xrayBin.exists() || !xrayBin.canExecute()) {
                LogManager.addLog("❌ Binário não executável! existe=${xrayBin.exists()} exec=${xrayBin.canExecute()}")
                
                // Tentar alternativa: executar direto da lib
                if (xraySo.exists()) {
                    xraySo.setExecutable(true, false)
                    LogManager.addLog("Tentando lib original: exec=${xraySo.canExecute()}")
                }
                return false
            }

            LogManager.addLog("✅ Binário pronto: ${xrayBin.absolutePath}")

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Usar /bin/sh -c para garantir execução
                    val cmd = arrayOf(
                        "/system/bin/sh", "-c",
                        "cd ${configDir.absolutePath} && chmod 755 ./xray && ./xray run -config ${configFile.absolutePath}"
                    )
                    
                    LogManager.addLog("Executando: ${cmd.joinToString(" ")}")
                    
                    val pb = ProcessBuilder(*cmd)
                        .directory(configDir)
                        .redirectErrorStream(true)
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Processo Xray iniciado")

                    // Ler saída
                    launch {
                        try {
                            process?.inputStream?.bufferedReader()?.use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val l = line!!
                                    if (l.isNotBlank()) {
                                        LogManager.addLog("XRAY: $l")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            LogManager.addLog("XRAY log err: ${e.message}")
                        }
                    }

                    val exit = process?.waitFor() ?: -1
                    LogManager.addLog("Xray finalizado: código $exit")
                    isRunning = false
                } catch (e: Exception) {
                    LogManager.addLog("❌ Erro processo: ${e.message}")
                    isRunning = false
                }
            }

            Thread.sleep(2000)
            return isRunning

        } catch (e: Exception) {
            LogManager.addLog("❌ Erro geral: ${e.message}")
            return false
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
        LogManager.addLog("Xray parado")
    }

    private fun buildConfigJson(c: VlessConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "warning") })
        
        root.put("inbounds", listOf(
            JSONObject().apply {
                put("tag", "socks-in")
                put("port", SOCKS_PORT)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
            }
        ))
        
        // VLESS outbound
        val vless = JSONObject()
        vless.put("protocol", "vless")
        vless.put("tag", "proxy")
        vless.put("settings", JSONObject().apply {
            put("vnext", listOf(JSONObject().apply {
                put("address", c.server)
                put("port", c.port)
                put("users", listOf(JSONObject().apply {
                    put("id", c.uuid)
                    put("encryption", c.encryption)
                    if (c.flow.isNotEmpty()) put("flow", c.flow)
                }))
            }))
        })
        
        // Stream settings
        val stream = JSONObject()
        stream.put("network", c.type)
        stream.put("security", c.security)
        
        if (c.type == "xhttp") {
            stream.put("xhttpSettings", JSONObject().apply {
                put("mode", c.mode)
                put("path", c.path)
                if (c.host.isNotEmpty()) put("host", c.host)
            })
        } else if (c.type == "ws") {
            stream.put("wsSettings", JSONObject().apply {
                put("path", c.path)
                if (c.host.isNotEmpty()) put("headers", JSONObject().apply {
                    put("Host", c.host)
                })
            })
        }
        
        if (c.security == "tls") {
            stream.put("tlsSettings", JSONObject().apply {
                put("serverName", c.sni.ifEmpty { c.server })
                put("allowInsecure", c.insecure || c.allowInsecure)
                if (c.alpn.isNotEmpty()) {
                    val alpnList = c.alpn.split(",").map { it.trim() }
                    put("alpn", alpnList.joinToString("|") { it })
                }
            })
        }
        
        vless.put("streamSettings", stream)
        
        root.put("outbounds", listOf(
            vless,
            JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
            }
        ))
        
        root.put("routing", JSONObject().apply {
            put("rules", listOf(JSONObject().apply {
                put("type", "field")
                put("inboundTag", listOf("socks-in"))
                put("outboundTag", "proxy")
            }))
        })
        
        return root.toString(2)
    }
}
