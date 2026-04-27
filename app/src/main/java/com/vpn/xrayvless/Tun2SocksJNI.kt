package com.vpn.xrayvless

object Tun2SocksJNI {
    private var loaded = false

    init {
        try {
            System.loadLibrary("tun2socks_jni")
            loaded = true
            LogManager.addLog("✅ tun2socks_jni carregado")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            LogManager.addLog("❌ tun2socks_jni: ${e.message}")
        }
    }

    external fun clearCloexec(fd: Int): Boolean
    external fun dupFd(fd: Int): Int

    fun isAvailable(): Boolean = loaded
}
