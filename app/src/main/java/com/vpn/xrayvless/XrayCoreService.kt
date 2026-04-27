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

    fun start(config: VlessConfig, tunFd: Int): Boolean {
        if (isRunning) return false

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            val json = buildXrayJson(config, tunFd)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("✅ Config salva (fd=$tunFd)")

            copyAssets(configDir)

            val xrayPath = File(context.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pb = ProcessBuilder(xrayPath, "run", "-config", configFile.absolutePath)
                        .directory(configDir)
                        .redirectErrorStream(true)
                    
                    // Passar o fd como variável de ambiente
                    pb.environment()["XRAY_TUN_FD"] = tunFd.toString()
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Xray rodando com TUN fd=$tunFd!")

                    launch {
                        var count = 0
                        process?.inputStream?.bufferedReader()?.use { reader ->
                            reader.lines().forEach { line ->
                                if (line.isNotBlank() && count < 20) {
                                    count++
                                    LogManager.addLog("X: $line")
                                }
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

    private fun buildXrayJson(c: VlessConfig, tunFd: Int): String {
        return """
{
  "log": {"loglevel": "warn"},
  "dns": {"servers": ["1.1.1.1", "8.8.8.8"]},
  "inbounds": [
    {
      "tag": "tun-in",
      "protocol": "tun",
      "settings": {
        "mtu": 1500,
        "fd": $tunFd,
        "stack": "system"
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls"]
      }
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [{
          "address": "${c.server}",
          "port": ${c.port},
          "users": [{
            "id": "${c.uuid}",
            "encryption": "${c.encryption}",
            "flow": "${c.flow}"
          }]
        }]
      },
      "streamSettings": {
        "network": "${c.type}",
        "security": "${c.security}",
        "tlsSettings": {
          "serverName": "${c.sni.ifEmpty { c.server }}",
          "allowInsecure": ${c.insecure || c.allowInsecure},
          "alpn": [${c.alpn.split(",").joinToString(", ") { "\"${it.trim()}\"" }}]
        },
        "xhttpSettings": {
          "host": "${c.host}",
          "mode": "${c.mode}",
          "path": "${c.path}"
        }
      }
    },
    {"tag": "direct", "protocol": "freedom"}
  ],
  "routing": {
    "rules": [
      {"type": "field", "inboundTag": ["tun-in"], "outboundTag": "proxy"}
    ]
  }
}
""".trimIndent()
    }

    private fun copyAssets(configDir: File) {
        listOf("geosite.dat", "geoip.dat").forEach { name ->
            val dest = File(configDir, name)
            if (!dest.exists()) {
                try {
                    context.assets.open(name).use { it.copyTo(dest.outputStream()) }
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
