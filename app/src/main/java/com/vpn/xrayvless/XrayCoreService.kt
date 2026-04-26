package com.vpn.xrayvless

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class XrayCoreService(private val context: Context) {

    companion object {
        const val DOKODEMO_PORT = 10808
    }

    private var isRunning = false
    private var job: Job? = null
    private var process: Process? = null

    fun start(config: VlessConfig): Boolean {
        if (isRunning) return false

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            val json = buildXrayJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("✅ Config salva")

            copyAssets(configDir)

            val xrayPath = findXrayExecutable(configDir)
            if (xrayPath == null) {
                LogManager.addLog("❌ Xray não encontrado")
                return false
            }

            LogManager.addLog("✅ Executável pronto")

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pb = ProcessBuilder(xrayPath, "run", "-config", configFile.absolutePath)
                        .directory(configDir)
                        .redirectErrorStream(true)
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Xray rodando (dokodemo:$DOKODEMO_PORT)")

                    launch {
                        try {
                            process?.inputStream?.bufferedReader()?.use { reader ->
                                var count = 0
                                reader.lines().forEach { line ->
                                    if (line.isNotBlank() && count < 20) {
                                        // Só mostrar coisas importantes
                                        if (line.contains("started") || line.contains("Failed") || line.contains("Error") && !line.contains("loopback")) {
                                            count++
                                            LogManager.addLog("X: $line")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }

                    val exit = process?.waitFor() ?: -1
                    LogManager.addLog("Xray fim: $exit")
                    isRunning = false
                } catch (e: Exception) {
                    LogManager.addLog("❌ Processo: ${e.message}")
                    isRunning = false
                }
            }

            Thread.sleep(2000)
            return isRunning

        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            return false
        }
    }

    private fun buildXrayJson(c: VlessConfig): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"log\": {")
        sb.appendLine("    \"loglevel\": \"error\"")
        sb.appendLine("  },")
        sb.appendLine("  \"inbounds\": [{")
        sb.appendLine("    \"tag\": \"vpn-in\",")
        sb.appendLine("    \"port\": $DOKODEMO_PORT,")
        sb.appendLine("    \"listen\": \"127.0.0.1\",")
        sb.appendLine("    \"protocol\": \"dokodemo-door\",")
        sb.appendLine("    \"settings\": {")
        sb.appendLine("      \"network\": \"tcp,udp\",")
        sb.appendLine("      \"followRedirect\": true")
        sb.appendLine("    },")
        // Desabilitar detecção de loopback
        sb.appendLine("    \"sniffing\": {")
        sb.appendLine("      \"enabled\": false,")
        sb.appendLine("      \"destOverride\": []")
        sb.appendLine("    }")
        sb.appendLine("  }],")
        sb.appendLine("  \"outbounds\": [{")
        sb.appendLine("    \"tag\": \"proxy\",")
        sb.appendLine("    \"protocol\": \"vless\",")
        sb.appendLine("    \"settings\": {")
        sb.appendLine("      \"vnext\": [{")
        sb.appendLine("        \"address\": \"${c.server}\",")
        sb.appendLine("        \"port\": ${c.port},")
        sb.appendLine("        \"users\": [{")
        sb.appendLine("          \"id\": \"${c.uuid}\",")
        sb.appendLine("          \"encryption\": \"${c.encryption}\"")
        if (c.flow.isNotEmpty()) {
            sb.appendLine("          ,\"flow\": \"${c.flow}\"")
        }
        sb.appendLine("        }]")
        sb.appendLine("      }]")
        sb.appendLine("    },")
        sb.appendLine("    \"streamSettings\": {")
        sb.appendLine("      \"network\": \"${c.type}\",")
        sb.appendLine("      \"security\": \"${c.security}\"")
        
        if (c.type == "xhttp") {
            sb.appendLine("      ,\"xhttpSettings\": {")
            sb.appendLine("        \"mode\": \"${c.mode}\",")
            sb.appendLine("        \"path\": \"${c.path}\"")
            if (c.host.isNotEmpty()) {
                sb.appendLine("        ,\"host\": \"${c.host}\"")
            }
            sb.appendLine("      }")
        }
        
        if (c.security == "tls") {
            sb.appendLine("      ,\"tlsSettings\": {")
            sb.appendLine("        \"serverName\": \"${c.sni.ifEmpty { c.server }}\",")
            sb.appendLine("        \"allowInsecure\": ${c.insecure || c.allowInsecure}")
            if (c.alpn.isNotEmpty()) {
                val alpnArray = c.alpn.split(",").map { "\"${it.trim()}\"" }.joinToString(", ")
                sb.appendLine("        ,\"alpn\": [$alpnArray]")
            }
            sb.appendLine("      }")
        }
        
        sb.appendLine("    }")
        sb.appendLine("  }, {")
        sb.appendLine("    \"tag\": \"direct\",")
        sb.appendLine("    \"protocol\": \"freedom\"")
        sb.appendLine("  }],")
        sb.appendLine("  \"routing\": {")
        sb.appendLine("    \"rules\": [{")
        sb.appendLine("      \"type\": \"field\",")
        sb.appendLine("      \"inboundTag\": [\"vpn-in\"],")
        sb.appendLine("      \"outboundTag\": \"proxy\"")
        sb.appendLine("    }]")
        sb.appendLine("  }")
        sb.appendLine("}")
        
        return sb.toString()
    }

    private fun findXrayExecutable(configDir: File): String? {
        try {
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (nativeLib.exists()) {
                nativeLib.setExecutable(true, false)
                return nativeLib.absolutePath
            }
        } catch (e: Exception) {}
        return null
    }

    private fun copyAssets(configDir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { name ->
            val dest = File(configDir, name)
            if (!dest.exists()) {
                try {
                    context.assets.open(name).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
    }
}
