package com.vpn.xrayvless

import android.content.Context
import kotlinx.coroutines.*
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

            val json = buildXrayJson(config)
            File(configDir, "config.json").writeText(json)
            copyAssets(configDir)

            val xrayPath = File(context.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    process = ProcessBuilder(xrayPath, "run", "-config", File(configDir, "config.json").absolutePath)
                        .directory(configDir).redirectErrorStream(true).start()
                    isRunning = true
                    LogManager.addLog("✅ Xray SOCKS5:${SOCKS_PORT}")

                    launch {
                        var count = 0
                        process?.inputStream?.bufferedReader()?.use { reader ->
                            reader.lines().forEach { line ->
                                if (line.isNotBlank() && count < 5) { count++; LogManager.addLog("X: $line") }
                            }
                        }
                    }

                    process?.waitFor()
                    isRunning = false
                } catch (e: Exception) {
                    LogManager.addLog("❌ ${e.message}")
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
        return """
{
  "log": {"loglevel": "warn"},
  "inbounds": [{
    "tag": "socks-in",
    "port": $SOCKS_PORT,
    "listen": "127.0.0.1",
    "protocol": "socks",
    "settings": {"auth": "noauth", "udp": true}
  }],
  "outbounds": [{
    "tag": "proxy",
    "protocol": "vless",
    "settings": {"vnext": [{"address": "${c.server}", "port": ${c.port}, "users": [{"id": "${c.uuid}", "encryption": "${c.encryption}"}]}]},
    "streamSettings": {
      "network": "${c.type}",
      "security": "${c.security}",
      "tlsSettings": {"serverName": "${c.sni.ifEmpty { c.server }}", "allowInsecure": ${c.insecure || c.allowInsecure}},
      "xhttpSettings": {"host": "${c.host}", "mode": "${c.mode}", "path": "${c.path}"}
    }
  }, {"tag": "direct", "protocol": "freedom"}],
  "routing": {"rules": [{"type": "field", "inboundTag": ["socks-in"], "outboundTag": "proxy"}]}
}
""".trimIndent()
    }

    private fun copyAssets(configDir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { name ->
            val dest = File(configDir, name)
            if (!dest.exists()) {
                try { context.assets.open(name).use { it.copyTo(dest.outputStream()) } } catch (e: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
    }
}
