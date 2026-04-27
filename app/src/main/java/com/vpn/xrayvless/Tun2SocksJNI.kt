package com.vpn.xrayvless

import android.util.Log
import java.io.FileDescriptor

object Tun2SocksJNI {
    private const val TAG = "Tun2SocksJNI"
    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            Log.d(TAG, "Carregando libtun2socks...")
            System.loadLibrary("tun2socks")
            loaded = true
            Log.i(TAG, "✅ libtun2socks.so carregada!")
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            loadError = "UnsatisfiedLinkError: ${e.message}"
            Log.e(TAG, loadError!!)
        } catch (e: SecurityException) {
            loaded = false
            loadError = "SecurityException: ${e.message}"
            Log.e(TAG, loadError!!)
        } catch (e: Exception) {
            loaded = false
            loadError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, loadError!!)
        }
    }

    external fun StartTun2socks(tunFd: Int, socksAddr: String, mtu: Int)
    external fun StopTun2socks()

    fun isAvailable(): Boolean = loaded
    fun getError(): String? = loadError

    fun getFd(fileDescriptor: FileDescriptor): Int {
        return try {
            val field = FileDescriptor::class.java.getDeclaredField("fd")
            field.isAccessible = true
            field.getInt(fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "getFd error: ${e.message}")
            -1
        }
    }
}
