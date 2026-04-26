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

    private val xrayPath: String by lazy {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val xrayFile = File(nativeDir, "libxray.so")
        if (xrayFile.exists()) {
            LogManager.addLog("libxray.so: ${xrayFile.absolutePath}")
            xrayFile.absolutePath
        } else {
            LogManager.addLog("❌ libxray.so NÃO encontrada em: $nativeDir")
            nativeDir.listFiles()?.forEach {
                LogManager.addLog("  Arquivo: ${it.name}")
            }
            ""
        }
    }

    fun start(config: VlessConfig): Boolean {
        if (isRunning) return false

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            val json = buildConfigJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("Config JSON salva em: ${configFile.absolutePath}")
            LogManager.addLog("JSON: $json")

            val xrayBin = File(configDir, "xray")
            if (!xrayBin.exists() && xrayPath.isNotEmpty()) {
                File(xrayPath).copyTo(xrayBin, overwrite = true)
                xrayBin.setExecutable(true)
                LogManager.addLog("✅ Xray binário copiado")
            }

            if (!xrayBin.exists()) {
                LogManager.addLog("❌ Binário xray não disponível")
                return false
            }

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pb = ProcessBuilder(
                        xrayBin.absolutePath, "run",
                        "-config", configFile.absolutePath
                    )
                    pb.directory(configDir)
                    pb.redirectErrorStream(true)
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Xray Core iniciado (SOCKS: $SOCKS_PORT)")

                    // Ler logs do Xray
                    launch {
                        try {
                            process?.inputStream?.bufferedReader()?.use { reader ->
                                reader.lines().forEach { line ->
                                    if (line.contains("started") || line.contains("error") || line.contains("warning")) {
                                        LogManager.addLog("XRAY: $line")
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }

                    val exit = process?.waitFor() ?: -1
                    LogManager.addLog("Xray finalizado: código $exit")
                    isRunning = false
                } catch (e: Exception) {
                    LogManager.addLog("❌ Erro Xray: ${e.message}")
                    isRunning = false
                }
            }

            // Aguardar iniciar
            Thread.sleep(2000)
            return isRunning

        } catch (e: Exception) {
            LogManager.addLog("❌ Erro config: ${e.message}")
            return false
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
        LogManager.addLog("Xray Core parado")
    }

    private fun buildConfigJson(c: VlessConfig): String {
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", listOf(JSONObject().apply {
                put("tag", "socks-in")
                put("port", SOCKS_PORT)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
            }))
            put("outbounds", listOf(
                JSONObject().apply {
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", listOf(JSONObject().apply {
                            put("address", c.server)
                            put("port", c.port)
                            put("users", listOf(JSONObject().apply {
                                put("id", c.uuid)
                                put("encryption", c.encryption)
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
                                if (c.alpn.isNotEmpty()) put("alpn", c.alpn.split(","))
                            })
                        }
                    })
                },
                JSONObject().apply { put("protocol", "freedom") }
            ))
        }.toString(2)
    }
}
