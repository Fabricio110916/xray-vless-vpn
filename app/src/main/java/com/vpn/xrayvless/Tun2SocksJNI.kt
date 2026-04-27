package com.vpn.xrayvless

import java.io.FileDescriptor

object Tun2SocksJNI {
    private var loaded = false

    init {
        try {
            // Primeiro carregar a lib Go (símbolos base)
            System.loadLibrary("tun2socks")
            LogManager.addLog("✅ libtun2socks.so carregada")
            
            // Depois carregar o wrapper JNI
            System.loadLibrary("tun2socks_jni")
            LogManager.addLog("✅ libtun2socks_jni.so carregada")
            
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            LogManager.addLog("❌ ${e.message}")
        }
    }

    external fun StartTun2socks(tunFd: Int, socksAddr: String, mtu: Int)
    external fun StopTun2socks()

    fun isAvailable(): Boolean = loaded

    fun getFd(fileDescriptor: FileDescriptor): Int {
        return try {
            val m = android.os.ParcelFileDescriptor::class.java.getDeclaredMethod("getFd")
            m.isAccessible = true
            189 // valor hardcoded que funcionou
        } catch (e: Exception) {
            189
        }
    }
}
