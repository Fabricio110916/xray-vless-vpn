package com.vpn.xrayvless

import java.io.FileDescriptor

object Tun2SocksJNI {
    private var loaded = false

    init {
        try {
            System.loadLibrary("tun2socks")      // Lib Go (símbolos StartTun2socks)
            System.loadLibrary("tun2socks_jni")  // Wrapper JNI (ponte Java->Go)
            loaded = true
            LogManager.addLog("✅ Tun2Socks JNI completo!")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            LogManager.addLog("❌ ${e.message}")
        }
    }

    external fun StartTun2socks(tunFd: Int, socksAddr: String, mtu: Int)
    external fun StopTun2socks()

    fun isAvailable(): Boolean = loaded
    fun getError(): String? = if (loaded) null else "JNI não carregado"

    fun getFd(fd: FileDescriptor): Int {
        return try {
            val f = FileDescriptor::class.java.getDeclaredField("fd")
            f.isAccessible = true
            f.getInt(fd)
        } catch (e: Exception) { -1 }
    }
}
