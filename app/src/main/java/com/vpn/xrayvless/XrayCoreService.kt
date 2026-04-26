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

    fun start(config: VlessConfig): Boolean {
        if (isRunning) return false

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            // Salvar config.json
            val json = buildConfigJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("✅ Config salva (${json.length} chars)")

            // Copiar assets
            copyAssets(configDir)

            // Encontrar executável
            val xrayPath = findXrayExecutable(configDir)
            if (xrayPath == null) {
                LogManager.addLog("❌ Xray não encontrado")
                return false
            }

            LogManager.addLog("✅ Executável: $xrayPath")

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Método 1: ProcessBuilder direto
                    try {
                        LogManager.addLog("Método 1: ProcessBuilder direto...")
                        val pb = ProcessBuilder(xrayPath, "run", "-config", configFile.absolutePath)
                            .directory(configDir)
                            .redirectErrorStream(true)
                        
                        process = pb.start()
                        isRunning = true
                        LogManager.addLog("✅ Xray iniciado (SOCKS:$SOCKS_PORT)")
                    } catch (e1: Exception) {
                        LogManager.addLog("Método 1 falhou: ${e1.message}")
                        
                        // Método 2: Via shell
                        try {
                            LogManager.addLog("Método 2: Via shell...")
                            val pb = ProcessBuilder(
                                "sh", "-c",
                                "cd ${configDir.absolutePath} && $xrayPath run -config ${configFile.absolutePath}"
                            ).redirectErrorStream(true)
                            
                            process = pb.start()
                            isRunning = true
                            LogManager.addLog("✅ Xray iniciado via shell")
                        } catch (e2: Exception) {
                            LogManager.addLog("Método 2 falhou: ${e2.message}")
                            
                            // Método 3: Runtime.exec
                            try {
                                LogManager.addLog("Método 3: Runtime.exec...")
                                process = Runtime.getRuntime().exec(
                                    arrayOf(xrayPath, "run", "-config", configFile.absolutePath),
                                    null, configDir
                                )
                                isRunning = true
                                LogManager.addLog("✅ Xray iniciado via Runtime")
                            } catch (e3: Exception) {
                                LogManager.addLog("❌ Todos os métodos falharam!")
                            }
                        }
                    }

                    // Se conseguiu iniciar, ler logs
                    if (isRunning && process != null) {
                        launch {
                            try {
                                process?.inputStream?.bufferedReader()?.use { reader ->
                                    var count = 0
                                    reader.lines().forEach { line ->
                                        if (line.isNotBlank() && count < 30) {
                                            count++
                                            LogManager.addLog("X: $line")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                LogManager.addLog("Log: ${e.message}")
                            }
                        }

                        val exit = process?.waitFor() ?: -1
                        LogManager.addLog("Xray fim: $exit")
                        isRunning = false
                    }
                } catch (e: Exception) {
                    LogManager.addLog("❌ Fatal: ${e.message}")
                    isRunning = false
                }
            }

            Thread.sleep(2500)
            LogManager.addLog("isRunning=$isRunning")
            return isRunning

        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            return false
        }
    }

    private fun findXrayExecutable(configDir: File): String? {
        // Método A: Direto do nativeLibraryDir (maior chance de funcionar)
        try {
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (nativeLib.exists() && nativeLib.canRead()) {
                nativeLib.setExecutable(true, false)
                LogManager.addLog("Método A: native dir - ${nativeLib.absolutePath}")
                return nativeLib.absolutePath
            }
        } catch (e: Exception) {
            LogManager.addLog("Método A falhou: ${e.message}")
        }

        // Método B: Copiar para configDir e dar permissão
        try {
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (nativeLib.exists()) {
                val xrayBin = File(configDir, "xray")
                LogManager.addLog("Método B: copiando para ${xrayBin.absolutePath}")
                
                nativeLib.inputStream().use { input ->
                    FileOutputStream(xrayBin).use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Verificar permissões
                val chmodResult = Runtime.getRuntime().exec(
                    arrayOf("chmod", "755", xrayBin.absolutePath)
                ).waitFor()
                
                LogManager.addLog("  chmod result=$chmodResult exec=${xrayBin.canExecute()}")
                
                if (xrayBin.canExecute() || chmodResult == 0) {
                    return xrayBin.absolutePath
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("Método B falhou: ${e.message}")
        }

        // Método C: Copiar para cache dir
        try {
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (nativeLib.exists()) {
                val cacheBin = File(context.cacheDir, "xray")
                LogManager.addLog("Método C: copiando para cache")
                
                nativeLib.inputStream().use { input ->
                    FileOutputStream(cacheBin).use { output ->
                        input.copyTo(output)
                    }
                }
                
                Runtime.getRuntime().exec(arrayOf("chmod", "755", cacheBin.absolutePath)).waitFor()
                
                if (cacheBin.canExecute()) {
                    return cacheBin.absolutePath
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("Método C falhou: ${e.message}")
        }

        return null
    }

    private fun copyAssets(configDir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { name ->
            val dest = File(configDir, name)
            if (!dest.exists()) {
                try {
                    context.assets.open(name).use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    LogManager.addLog("✅ $name (${dest.length()} bytes)")
                } catch (e: Exception) {
                    LogManager.addLog("⚠️ $name: ${e.message}")
                }
            } else {
                LogManager.addLog("✅ $name já existe (${dest.length()} bytes)")
            }
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
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
        }.toString(2)
    }
}
