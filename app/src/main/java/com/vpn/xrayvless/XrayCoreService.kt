package com.vpn.xrayvless

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class XrayCoreService(private val context: Context) {

    companion object {
        const val SOCKS_PORT = 10808
    }

    private var isRunning = false
    private var job: Job? = null
    private var process: Process? = null

    private fun copyAssetIfNeeded(srcName: String, destDir: File, destName: String): Boolean {
        val dest = File(destDir, destName)
        if (dest.exists()) return true
        
        return try {
            // Tentar copiar do mesmo diretório da libxray.so
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val src = File(nativeDir, srcName)
            
            if (src.exists()) {
                LogManager.addLog("Copiando $srcName de $nativeDir")
                src.inputStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                LogManager.addLog("✅ $destName: ${dest.length()} bytes")
                true
            } else {
                LogManager.addLog("⚠️ $srcName não encontrado em $nativeDir")
                // Listar o que tem no diretório
                nativeDir.listFiles()?.forEach {
                    LogManager.addLog("  ${it.name} (${it.length()})")
                }
                false
            }
        } catch (e: Exception) {
            LogManager.addLog("❌ Erro ao copiar $srcName: ${e.message}")
            false
        }
    }

    fun start(config: VlessConfig): Boolean {
        if (isRunning) return false

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            // 1. Copiar executável xray
            val xrayBin = File(configDir, "xray")
            if (!xrayBin.exists()) {
                val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
                if (nativeLib.exists()) {
                    LogManager.addLog("Copiando executável (${nativeLib.length()} bytes)")
                    nativeLib.inputStream().use { input ->
                        FileOutputStream(xrayBin).use { output ->
                            input.copyTo(output)
                        }
                    }
                    xrayBin.setExecutable(true, false)
                    xrayBin.setReadable(true)
                    LogManager.addLog("✅ xray: ${xrayBin.length()} bytes, exec=${xrayBin.canExecute()}")
                } else {
                    LogManager.addLog("❌ libxray.so não encontrada")
                    return false
                }
            }

            // 2. Copiar geosite.dat e geoip.dat
            copyAssetIfNeeded("geosite.dat", configDir, "geosite.dat")
            copyAssetIfNeeded("geoip.dat", configDir, "geoip.dat")

            // 3. Salvar config.json
            val json = buildConfigJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("Config JSON salva (${json.length} chars)")

            if (!xrayBin.canExecute()) {
                LogManager.addLog("❌ xray não executável")
                return false
            }

            LogManager.addLog("✅ Todos os arquivos prontos")

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val commands = arrayOf(
                        xrayBin.absolutePath, "run",
                        "-config", configFile.absolutePath
                    )
                    
                    LogManager.addLog("Executando: ${xrayBin.absolutePath}")
                    
                    val pb = ProcessBuilder(*commands)
                        .directory(configDir)
                        .redirectErrorStream(true)
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Processo Xray iniciado")

                    // Ler logs do Xray em tempo real
                    launch {
                        try {
                            process?.inputStream?.bufferedReader()?.use { reader ->
                                reader.lines().forEach { line ->
                                    if (line.isNotBlank()) {
                                        LogManager.addLog("X: $line")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            LogManager.addLog("Log: ${e.message}")
                        }
                    }

                    val exit = process?.waitFor() ?: -1
                    LogManager.addLog("Xray encerrado: código $exit")
                    isRunning = false
                } catch (e: Exception) {
                    LogManager.addLog("❌ Processo: ${e.message}")
                    isRunning = false
                }
            }

            // Aguardar iniciar
            Thread.sleep(2500)
            LogManager.addLog("Status: isRunning=$isRunning")
            return isRunning

        } catch (e: Exception) {
            LogManager.addLog("❌ Erro: ${e.message}")
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
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", listOf(JSONObject().apply {
                put("tag", "socks-in")
                put("port", SOCKS_PORT)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
            }))
            put("outbounds", listOf(
                JSONObject().apply {
                    put("tag", "proxy")
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
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
                    put("streamSettings", JSONObject().apply {
                        put("network", c.type)
                        put("security", c.security)
                        if (c.type == "xhttp") {
                            put("xhttpSettings", JSONObject().apply {
                                put("mode", c.mode)
                                put("path", c.path)
                                if (c.host.isNotEmpty()) put("host", c.host)
                            })
                        }
                        if (c.security == "tls") {
                            put("tlsSettings", JSONObject().apply {
                                put("serverName", c.sni.ifEmpty { c.server })
                                put("allowInsecure", c.insecure || c.allowInsecure)
                                if (c.alpn.isNotEmpty()) put("alpn", c.alpn.split(",").map { it.trim() })
                            })
                        }
                    })
                },
                JSONObject().apply { put("protocol", "freedom") }
            ))
            put("routing", JSONObject().apply {
                put("rules", listOf(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", listOf("socks-in"))
                    put("outboundTag", "proxy")
                }))
            })
        }.toString(2)
    }
}
