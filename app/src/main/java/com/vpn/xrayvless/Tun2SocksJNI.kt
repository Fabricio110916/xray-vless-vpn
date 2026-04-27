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
            LogManager.addLog("❌ ${e.message}")
        }
    }

    // Métodos JNI do wrapper C
    external fun clearCloexec(fd: Int): Boolean
    external fun dupFd(fd: Int): Int

    // Métodos da lib Go (carregada pelo wrapper)
    // Estes são chamados via o init do tun2socks_v2.c
    fun StartTun2socks(fd: Int, socksAddr: String, mtu: Int) {
        nativeStart(fd, socksAddr, mtu)
    }
    
    fun StopTun2socks() {
        nativeStop()
    }

    private external fun nativeStart(fd: Int, socksAddr: String, mtu: Int)
    private external fun nativeStop()

    fun isAvailable(): Boolean = loaded
}
