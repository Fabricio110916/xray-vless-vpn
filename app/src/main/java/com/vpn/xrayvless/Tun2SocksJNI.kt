package com.vpn.xrayvless

import android.util.Log
import java.io.FileDescriptor

object Tun2SocksJNI {
    private const val TAG = "Tun2SocksJNI"
    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            Log.d(TAG, "Tentando carregar libtun2socks...")
            System.loadLibrary("tun2socks")
            loaded = true
            LogManager.addLog("✅ libtun2socks.so carregada!")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            loadError = e.message
            LogManager.addLog("❌ libtun2socks.so ERRO: ${e.message}")
            Log.e(TAG, "UnsatisfiedLinkError", e)
        } catch (e: SecurityException) {
            loaded = false
            loadError = e.message
            LogManager.addLog("❌ libtun2socks.so SEGURANÇA: ${e.message}")
        } catch (e: Exception) {
            loaded = false
            loadError = e.message
            LogManager.addLog("❌ libtun2socks.so OUTRO: ${e.message}")
        }
    }

    external fun StartTun2socks(tunFd: Int, socksAddr: String, mtu: Int)
    external fun StopTun2socks()

    fun isAvailable(): Boolean = loaded

    fun getFd(fileDescriptor: FileDescriptor): Int {
        return try {
            val field = FileDescriptor::class.java.getDeclaredField("fd")
            field.isAccessible = true
            val fd = field.getInt(fileDescriptor)
            LogManager.addLog("📎 FD obtido: $fd")
            fd
        } catch (e: Exception) {
            LogManager.addLog("❌ getFd erro: ${e.message}")
            -1
        }
    }
}
