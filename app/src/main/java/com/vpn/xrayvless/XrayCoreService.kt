package com.vpn.xrayvless

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class XrayCoreService(private val context: Context) {

    companion object {
        private const val TAG = "XrayCoreService"
        const val SOCKS_PORT = 10808
    }

    private var isRunning = false
    private var job: Job? = null
    private var process: Process? = null

    private val xrayPath: String by lazy {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val xrayFile = File(nativeDir, "libxray.so")
        if (xrayFile.exists()) {
            Log.d(TAG, "libxray.so encontrada em: ${xrayFile.absolutePath}")
            xrayFile.absolutePath
        } else {
            Log.e(TAG, "libxray.so NAO encontrada em: $nativeDir")
            ""
        }
    }

    fun start(config: VlessConfig): Boolean {
        if (isRunning) {
            Log.w(TAG, "Xray ja esta rodando")
            return false
        }

        try {
            val configDir = File(context.filesDir, "xray")
            configDir.mkdirs()

            val json = buildConfigJson(config)
            val configFile = File(configDir, "config.json")
            configFile.writeText(json)
            Log.d(TAG, "Config gerada com sucesso")

            val xrayBin = File(configDir, "xray")
            if (!xrayBin.exists() && xrayPath.isNotEmpty()) {
                File(xrayPath).copyTo(xrayBin, overwrite = true)
                xrayBin.setExecutable(true)
                Log.d(TAG, "Xray copiado para: ${xrayBin.absolutePath}")
            }

            if (!xrayBin.exists()) {
                Log.e(TAG, "Binario xray nao disponivel")
                return false
            }

            job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pb = ProcessBuilder(xrayBin.absolutePath, "run", "-config", configFile.absolutePath)
                    pb.directory(configDir)
                    pb.redirectErrorStream(true)

                    process = pb.start()
                    isRunning = true
                    Log.d(TAG, "✅ Xray Core iniciado")

                    launch {
                        process?.inputStream?.bufferedReader()?.use { reader ->
                            reader.lines().forEach { line ->
                                Log.d(TAG, "XRAY: $line")
                            }
                        }
                    }

                    val exit = process?.waitFor() ?: -1
                    Log.d(TAG, "Xray finalizado: $exit")
                    isRunning = false
                } catch (e: Exception) {
                    Log.e(TAG, "Erro Xray: ${e.message}", e)
                    isRunning = false
                }
            }

            Thread.sleep(2000)
            return isRunning

        } catch (e: Exception) {
            Log.e(TAG, "Erro config: ${e.message}", e)
            return false
        }
    }

    fun stop() {
        isRunning = false
        process?.destroy()
        job?.cancel()
        Log.d(TAG, "Xray parado")
    }

    private fun buildConfigJson(c: VlessConfig): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        root.put("inbounds", listOf(JSONObject().apply {
            put("tag", "socks-in")
            put("port", SOCKS_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        }))

        val out = JSONObject()
        out.put("tag", "proxy")
        out.put("protocol", "vless")
        out.put("settings", JSONObject().apply {
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

        val stream = JSONObject()
        stream.put("network", c.type)
        stream.put("security", c.security)

        if (c.type == "ws") {
            val ws = JSONObject()
            ws.put("path", c.path)
            if (c.host.isNotEmpty()) {
                ws.put("headers", JSONObject().apply { put("Host", c.host) })
            }
            stream.put("wsSettings", ws)
        }
        
        if (c.type == "xhttp") {
            val xhttp = JSONObject()
            xhttp.put("mode", c.mode)
            xhttp.put("path", c.path)
            if (c.host.isNotEmpty()) {
                xhttp.put("host", c.host)
            }
            stream.put("xhttpSettings", xhttp)
        }
        
        if (c.security == "tls") {
            val tls = JSONObject()
            tls.put("serverName", c.sni.ifEmpty { c.server })
            tls.put("allowInsecure", c.insecure || c.allowInsecure)
            if (c.alpn.isNotEmpty()) {
                tls.put("alpn", c.alpn.split(","))
            }
            stream.put("tlsSettings", tls)
        }

        out.put("streamSettings", stream)

        root.put("outbounds", listOf(
            out,
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
