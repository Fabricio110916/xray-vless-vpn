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
    private var tun2socksProc: java.lang.Process? = null

    override fun onBind(i: Intent?) = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        try {
            LogManager.init(this)
            Tun2SocksJNI.isAvailable()
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

            var fd = vpnInterface!!.fd
            LogManager.addLog("FD=$fd")
            val dupFd = Tun2SocksJNI.dupFd(fd)
            if (dupFd > 0) {
                LogManager.addLog("✅ fd duplicado: $fd -> $dupFd")
                fd = dupFd
            }

            // Limpar FD_CLOEXEC para o fd sobreviver ao exec()
            val cleared = Tun2SocksJNI.clearCloexec(fd)
            LogManager.addLog(if (cleared) "✅ FD_CLOEXEC limpo fd=$fd" else "❌ FD_CLOEXEC NÃO limpo!")

            val nativeDir = applicationInfo.nativeLibraryDir
            val exeFile = File(nativeDir, "libtun2socks_exec.so")
            LogManager.addLog("exe existe=${exeFile.exists()} path=${exeFile.absolutePath}")

            if (!exeFile.exists()) {
                LogManager.addLog("❌ libtun2socks_exec.so não encontrado")
                stopSelf(); return START_NOT_STICKY
            }

            // Garantir permissão de execução (igual ao libxray.so)
            exeFile.setExecutable(true, false)

            Thread({
                try {
                    // ✅ Rodar DIRETO sem linker64 — igual ao libxray.so
                    // O linker64 não passa os args Go corretamente
                    val cmd = arrayOf(
                        exeFile.absolutePath,
                        "-device", "fd://$fd",
                        "-proxy",  "socks5://127.0.0.1:${XrayCoreService.SOCKS_PORT}",
                        "-loglevel", "warn"
                    )
                    LogManager.addLog("▶ ${cmd.joinToString(" ")}")
                    val pb = ProcessBuilder(*cmd)
                    pb.environment()["LD_LIBRARY_PATH"] = nativeDir
                    pb.redirectErrorStream(true)
                    tun2socksProc = pb.start() as java.lang.Process
                    LogManager.addLog("✅ tun2socks iniciado")
                    (tun2socksProc as java.lang.Process).inputStream.bufferedReader().use { br ->
                        var l: String?
                        while (br.readLine().also { l = it } != null) {
                            LogManager.addLog("TUN: $l")
                        }
                    }
                    val exit = (tun2socksProc as java.lang.Process).exitValue()
                    LogManager.addLog("TUN fim exitCode=$exit")
                    if (running) { running = false; stopSelf() }
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
        try {
            (tun2socksProc as? java.lang.Process)?.destroy()
            LogManager.addLog("⛔ tun2socks destruído")
        } catch (e: Exception) {}
        try { xray?.stop() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
// fix warn 1777320988
