package com.vpn.xrayvless

import android.content.Context
import kotlinx.coroutines.*
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

            val json = buildXrayJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("✅ Config salva (${json.length} chars)")
            LogManager.addLog("JSON gerado:\n$json")

            copyAssets(configDir)

            val xrayPath = findXrayExecutable(configDir)
            if (xrayPath == null) {
                LogManager.addLog("❌ Xray não encontrado")
                return false
            }

            LogManager.addLog("✅ Executável: $xrayPath")

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pb = ProcessBuilder(xrayPath, "run", "-config", configFile.absolutePath)
                        .directory(configDir)
                        .redirectErrorStream(true)
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Xray iniciado (SOCKS:$SOCKS_PORT)")

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

            Thread.sleep(2500)
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
        sb.appendLine("    \"loglevel\": \"warning\"")
        sb.appendLine("  },")
        sb.appendLine("  \"inbounds\": [{")
        sb.appendLine("    \"tag\": \"socks-in\",")
        sb.appendLine("    \"port\": $SOCKS_PORT,")
        sb.appendLine("    \"listen\": \"127.0.0.1\",")
        sb.appendLine("    \"protocol\": \"socks\",")
        sb.appendLine("    \"settings\": {")
        sb.appendLine("      \"auth\": \"noauth\",")
        sb.appendLine("      \"udp\": true")
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
        sb.appendLine("  }]")
        sb.appendLine("}")
        
        return sb.toString()
    }

    private fun findXrayExecutable(configDir: File): String? {
        try {
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (nativeLib.exists()) {
                nativeLib.setExecutable(true, false)
                LogManager.addLog("Usando native dir")
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
                    LogManager.addLog("✅ $name")
                } catch (e: Exception) {
                    LogManager.addLog("⚠️ $name: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
    }
}
