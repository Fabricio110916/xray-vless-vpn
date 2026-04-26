package com.vpn.xrayvless

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LogManager {
    private var logFile: File? = null
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var initCount = 0

    fun init(context: Context) {
        initCount++
        if (logFile == null) {
            logFile = File(context.filesDir, "xray_app_logs.txt")
        }
        // NÃO limpar logs - apenas adicionar separador
        if (initCount == 1) {
            addLog("=== APP INICIADO ===")
            addLog("Hora: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
        } else {
            addLog("--- XrayVpnService iniciado (init #$initCount) ---")
        }
    }

    @Synchronized
    fun addLog(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $msg"
        android.util.Log.d("XRAY_APP", line)
        
        try {
            logFile?.appendText(line + "\n")
        } catch (e: Exception) {
            android.util.Log.e("XRAY_APP", "Erro log: ${e.message}")
        }

        for (listener in listeners) {
            try { listener.invoke(line) } catch (e: Exception) {}
        }
    }

    fun getLogs(): String {
        return try {
            logFile?.readText() ?: "Sem logs"
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
