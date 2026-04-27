package com.vpn.xrayvless

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.File

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var xray: XrayCoreService? = null
    private var tun2socksProc: Process? = null

    override fun onBind(i: Intent?) = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        try {
            LogManager.init(this)
            Tun2SocksJNI.isAvailable() // força carregamento da JNI cedo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(
                        NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW)
                    )
            }
        } catch (e: Exception) {}
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val n = NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else
                startForeground(1, n)

            val c = Gson().fromJson(i?.getStringExtra("vless_config"), VlessConfig::class.java)

            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { stopSelf(); return START_NOT_STICKY }

            val b = Builder()
                .setSession(c.remark)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = b.establish() ?: run { stopSelf(); return START_NOT_STICKY }
            running = true

            val fd = vpnInterface!!.fd
            LogManager.addLog("FD=$fd")

            // ✅ CRÍTICO: limpar FD_CLOEXEC antes do exec()
            val cleared = Tun2SocksJNI.clearCloexec(fd)
            LogManager.addLog(if (cleared) "✅ FD_CLOEXEC limpo fd=$fd" else "❌ FD_CLOEXEC NÃO limpo!")

            val nativeDir = applicationInfo.nativeLibraryDir
            val exeFile = File(nativeDir, "libtun2socks_exec.so")
            LogManager.addLog("exe existe=${exeFile.exists()}")

            if (!exeFile.exists()) {
                LogManager.addLog("❌ libtun2socks_exec.so não encontrado em $nativeDir")
                stopSelf(); return START_NOT_STICKY
            }

            Thread({
                try {
                    val cmd = arrayOf(
                        "/system/bin/linker64",
                        exeFile.absolutePath,
                        "-device", "fd://$fd",
                        "-proxy",  "socks5://127.0.0.1:${XrayCoreService.SOCKS_PORT}",
                        "-loglevel", "warning"
                    )
                    LogManager.addLog("▶ ${cmd.joinToString(" ")}")
                    val pb = ProcessBuilder(*cmd)
                    pb.environment()["LD_LIBRARY_PATH"] = nativeDir
                    pb.redirectErrorStream(true)
                    tun2socksProc = pb.start()
                    LogManager.addLog("✅ tun2socks processo iniciado pid=${tun2socksProc?.pid()}")
                    tun2socksProc!!.inputStream.bufferedReader().use { br ->
                        var l: String?
                        while (br.readLine().also { l = it } != null) {
                            LogManager.addLog("TUN: $l")
                        }
                    }
                    LogManager.addLog("TUN fim: ${tun2socksProc!!.exitValue()}")
                } catch (e: Exception) {
                    LogManager.addLog("❌ tun2socks: ${e.message}")
                }
            }, "tun2socks").start()

        } catch (e: Exception) {
            LogManager.addLog("❌ ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { tun2socksProc?.destroy() } catch (e: Exception) {}
        try { xray?.stop() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
