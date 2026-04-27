package com.vpn.xrayvless

object Tun2SocksJNI {
    private var loaded = false

    init {
        try {
            System.loadLibrary("tun2socks_jni")
            loaded = true
            LogManager.addLog("✅ tun2socks_jni carregado")
        } catch (e: UnsatisfiedLinkError) {
            LogManager.addLog("❌ tun2socks_jni: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = loaded

    /** Limpa FD_CLOEXEC — DEVE ser chamado antes do exec() do tun2socks */
    fun clearCloexec(fd: Int): Boolean {
        if (!loaded) { LogManager.addLog("⚠️ JNI não carregado, FD_CLOEXEC não limpo!"); return false }
        return try {
            nativeClearCloexec(fd) >= 0
        } catch (e: Exception) {
            LogManager.addLog("❌ clearCloexec: ${e.message}")
            false
        }
    }

    external fun nativeClearCloexec(fd: Int): Int
    external fun StartTun2socks(tunFd: Int, socksAddr: String, mtu: Int)
    external fun StopTun2socks()
}
