package com.vpn.xrayvless

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    private const val MAX_LINES = 500
    private var logFile: File? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    fun init(context: Context) {
        logFile = File(context.filesDir, "app_logs.txt")
        addLog("=== APP INICIADO ===")
        addLog("Hora: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
    }

    fun addLog(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $msg"
        println(line)
        
        try {
            logFile?.appendText(line + "\n")
            // Manter apenas últimas linhas
            trimLogs()
        } catch (e: Exception) {}

        listeners.forEach { it.invoke(line) }
    }

    fun getLogs(): String {
        return try {
            logFile?.readText() ?: "Sem logs"
        } catch (e: Exception) {
            "Erro ao ler logs: ${e.message}"
        }
    }

    fun clear() {
        logFile?.writeText("")
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    private fun trimLogs() {
        try {
            val lines = logFile?.readLines() ?: return
            if (lines.size > MAX_LINES) {
                logFile?.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {}
    }
}
