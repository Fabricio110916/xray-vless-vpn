package com.vpn.xrayvless

import android.util.Log
import java.io.FileDescriptor

/**
 * Wrapper JNI para o tun2socks nativo
 */
object Tun2SocksJNI {
    private const val TAG = "Tun2SocksJNI"
    private var loaded = false

    init {
        try {
            System.loadLibrary("tun2socks")
            loaded = true
            LogManager.addLog("✅ libtun2socks.so carregada")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            LogManager.addLog("❌ libtun2socks.so NÃO encontrada!")
            LogManager.addLog("   Erro: ${e.message}")
        }
    }

    /**
     * Inicia o tun2socks
     * @param fd File descriptor da interface TUN
     * @param socksAddr Endereço SOCKS5 (ex: 127.0.0.1:10808)
     * @param mtu MTU da interface
     */
    external fun start(fd: Int, socksAddr: String, mtu: Int)

    /**
     * Para o tun2socks
     */
    external fun stop()

    fun isAvailable(): Boolean = loaded

    /**
     * Obtém o fd numérico de um FileDescriptor
     */
    fun getFd(fileDescriptor: FileDescriptor): Int {
        return try {
            val field = FileDescriptor::class.java.getDeclaredField("fd")
            field.isAccessible = true
            field.getInt(fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter fd: ${e.message}")
            -1
        }
    }
}
