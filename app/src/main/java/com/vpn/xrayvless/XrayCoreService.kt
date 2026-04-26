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
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            LogManager.addLog("✅ Config completa salva")

            copyAssets(configDir)

            val xrayPath = findXrayExecutable(configDir)
            if (xrayPath == null) {
                LogManager.addLog("❌ Xray não encontrado")
                return false
            }

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pb = ProcessBuilder(xrayPath, "run", "-config", configFile.absolutePath)
                        .directory(configDir)
                        .redirectErrorStream(true)
                    
                    process = pb.start()
                    isRunning = true
                    LogManager.addLog("✅ Xray SOCKS5 rodando")

                    launch {
                        process?.inputStream?.bufferedReader()?.use { reader ->
                            var count = 0
                            reader.lines().forEach { line ->
                                if (line.isNotBlank() && count < 5) {
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

    private fun buildXrayJson(c: VlessConfig): String {
        return """
{
  "dns": {
    "hosts": {
      "ofertas.tim.com.br": ["177.135.216.197", "177.135.216.202"]
    },
    "servers": ["1.1.1.1", "8.8.8.8"]
  },
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": $SOCKS_PORT,
      "protocol": "socks",
      "settings": {
        "auth": "noauth",
        "udp": true,
        "userLevel": 8
      },
      "sniffing": {
        "destOverride": ["http", "tls"],
        "enabled": true,
        "routeOnly": false
      },
      "tag": "socks"
    }
  ],
  "outbounds": [
    {
      "mux": {
        "concurrency": -1,
        "enabled": false
      },
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "${c.server}",
            "port": ${c.port},
            "users": [
              {
                "encryption": "${c.encryption}",
                "flow": "${c.flow}",
                "id": "${c.uuid}",
                "level": 8
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "${c.type}",
        "security": "${c.security}",
        "sockopt": {
          "domainStrategy": "UseIP"
        },
        "tlsSettings": {
          "allowInsecure": ${c.insecure || c.allowInsecure},
          "alpn": [${c.alpn.split(",").joinToString(", ") { "\"${it.trim()}\"" }}],
          "fingerprint": "${c.fp}",
          "serverName": "${c.sni.ifEmpty { c.server }}",
          "show": false
        },
        "xhttpSettings": {
          "host": "${c.host}",
          "mode": "${c.mode}",
          "path": "${c.path}"
        }
      },
      "tag": "proxy"
    },
    {
      "protocol": "freedom",
      "settings": {
        "domainStrategy": "UseIP"
      },
      "tag": "direct"
    },
    {
      "protocol": "blackhole",
      "settings": {
        "response": {
          "type": "http"
        }
      },
      "tag": "block"
    }
  ],
  "policy": {
    "levels": {
      "8": {
        "connIdle": 300,
        "downlinkOnly": 1,
        "handshake": 4,
        "uplinkOnly": 1
      }
    },
    "system": {
      "statsOutboundUplink": true,
      "statsOutboundDownlink": true
    }
  },
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      {
        "type": "field",
        "inboundTag": ["socks"],
        "outboundTag": "proxy"
      },
      {
        "type": "field",
        "ip": ["1.1.1.1"],
        "outboundTag": "proxy",
        "port": "53"
      },
      {
        "type": "field",
        "ip": ["8.8.8.8"],
        "outboundTag": "direct",
        "port": "53"
      }
    ]
  },
  "stats": {}
}
""".trimIndent()
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
