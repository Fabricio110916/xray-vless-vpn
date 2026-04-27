package com.vpn.xrayvless

import android.util.Log
import java.io.FileDescriptor

object Tun2SocksJNI {
    private const val TAG = "Tun2SocksJNI"
    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("tun2socks")
            loaded = true
            Log.i(TAG, "✅ libtun2socks.so carregada com sucesso!")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            loadError = e.message
            Log.e(TAG, "❌ libtun2socks.so NÃO carregou: ${e.message}")
        }
    }

    /**
     * Inicia o tun2socks
     * @param tunFd File descriptor da interface TUN (obtido via getFd)
     * @param socksAddr Endereço SOCKS5 (ex: "127.0.0.1:10808")
     * @param mtu MTU da interface
     */
    external fun StartTun2socks(tunFd: Int, socksAddr: String, mtu: Int)

    /**
     * Para o tun2socks
     */
    external fun StopTun2socks()

    fun isAvailable(): Boolean = loaded

    fun getError(): String? = loadError

    /**
     * Obtém o file descriptor numérico de um FileDescriptor
     * Isso é necessário porque o código Go espera um int (fd)
     */
    fun getFd(fileDescriptor: FileDescriptor): Int {
        return try {
            val field = FileDescriptor::class.java.getDeclaredField("fd")
            field.isAccessible = true
            val fd = field.getInt(fileDescriptor)
            Log.d(TAG, "FD obtido: $fd")
            fd
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao obter fd: ${e.message}")
            // Tentar método alternativo
            try {
                val method = FileDescriptor::class.java.getDeclaredMethod("getInt$")
                method.isAccessible = true
                val fd = method.invoke(fileDescriptor) as Int
                Log.d(TAG, "FD obtido (método 2): $fd")
                fd
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Todos os métodos falharam")
                -1
            }
        }
    }
}
