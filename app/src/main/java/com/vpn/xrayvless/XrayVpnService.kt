package com.vpn.xrayvless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class XrayVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var vpnThread: Thread? = null
    private var xray: XrayCoreService? = null
    private var packetCount = 0L
    private var errorCount = 0L
    private var socksOk = 0L
    private var socksFail = 0L

    override fun onBind(i: Intent?) = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        try {
            LogManager.init(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("x", "VPN", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Exception) {}
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val notification = NotificationCompat.Builder(this, "x")
                .setContentTitle("XRAY VPN").setContentText("Conectando...")
                .setSmallIcon(R.drawable.ic_chave_vpn).setOngoing(true).build()
            
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }

            val json = i?.getStringExtra("vless_config") ?: run { stopSelf(); return START_NOT_STICKY }
            val c = Gson().fromJson(json, VlessConfig::class.java)
            
            LogManager.addLog("Iniciando Xray Core...")
            xray = XrayCoreService(this)
            if (!xray!!.start(c)) { 
                LogManager.addLog("❌ Xray falhou")
                stopSelf()
                return START_NOT_STICKY 
            }
            LogManager.addLog("✅ Xray OK")
            
            LogManager.addLog("Criando VPN builder...")
            val builder = Builder()
                .setSession(c.remark)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
            
            // Excluir app da VPN
            try {
                builder.addDisallowedApplication(packageName)
                LogManager.addLog("✅ App ${packageName} excluído da VPN")
            } catch (e: Exception) {
                LogManager.addLog("⚠️ Não excluiu app: ${e.message}")
            }
            
            LogManager.addLog("Estabelecendo VPN...")
            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                LogManager.addLog("❌ VPN establish retornou null!")
                stopSelf()
                return START_NOT_STICKY
            }
            
            LogManager.addLog("✅ VPN estabelecida!")
            LogManager.addLog("FD: ${vpnInterface!!.fd}")
            LogManager.addLog("FD válido: ${vpnInterface!!.fileDescriptor.valid()}")
            
            // Proteger o socket SOCKS ANTES de iniciar o loop
            LogManager.addLog("Protegendo socket SOCKS 127.0.0.1:${XrayCoreService.SOCKS_PORT}...")
            try {
                val protectSocket = Socket()
                protectSocket.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
                val protected = protect(protectSocket)
                LogManager.addLog("✅ Socket SOCKS protegido: $protected")
                protectSocket.close()
            } catch (e: Exception) {
                LogManager.addLog("⚠️ Erro ao proteger SOCKS: ${e.message}")
            }
            
            // Também proteger DNS
            try {
                val dnsSocket = Socket()
                dnsSocket.connect(InetSocketAddress("1.1.1.1", 53), 3000)
                protect(dnsSocket)
                dnsSocket.close()
                LogManager.addLog("✅ DNS 1.1.1.1 protegido")
            } catch (e: Exception) {
                LogManager.addLog("⚠️ DNS: ${e.message}")
            }
            
            running = true
            packetCount = 0
            errorCount = 0
            socksOk = 0
            socksFail = 0
            
            vpnThread = Thread({
                LogManager.addLog("Thread VPN iniciada")
                tunnelLoop()
                LogManager.addLog("Thread VPN finalizada")
            }, "VPN-Tunnel")
            vpnThread?.start()
            
            LogManager.addLog("✅ Túnel VPN ativo")
            
        } catch (e: Exception) {
            LogManager.addLog("❌ ERRO: ${e.message}")
            LogManager.addLog("Stack: ${e.stackTraceToString().take(200)}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun tunnelLoop() {
        try {
            LogManager.addLog(">>> tunnelLoop iniciado")
            LogManager.addLog("Criando FileInputStream...")
            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            LogManager.addLog("✅ InputStream criado")
            
            LogManager.addLog("Criando FileOutputStream...")
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            LogManager.addLog("✅ OutputStream criado")
            
            val buffer = ByteArray(32767)
            var lastLog = System.currentTimeMillis()
            
            LogManager.addLog("🚀 Loop principal iniciado - aguardando pacotes...")
            
            while (running) {
                try {
                    val startRead = System.currentTimeMillis()
                    val length = inputStream.read(buffer)
                    val readTime = System.currentTimeMillis() - startRead
                    
                    if (length > 0) {
                        packetCount++
                        
                        // Log a cada 50 pacotes ou a cada 5 segundos
                        val now = System.currentTimeMillis()
                        if (packetCount % 50 == 0L || (now - lastLog > 5000 && packetCount > 0)) {
                            LogManager.addLog("📊 Stats: ${packetCount} pkts | SOCKS: ${socksOk}/${socksFail} | Erros: ${errorCount}")
                            lastLog = now
                        }
                        
                        // Log detalhado dos primeiros 5 pacotes
                        if (packetCount <= 5) {
                            val version = (buffer[0].toInt() shr 4) and 0x0F
                            val protocol = buffer[9].toInt() and 0xFF
                            val srcIP = "${buffer[12] and 0xFF}.${buffer[13] and 0xFF}.${buffer[14] and 0xFF}.${buffer[15] and 0xFF}"
                            val dstIP = "${buffer[16] and 0xFF}.${buffer[17] and 0xFF}.${buffer[18] and 0xFF}.${buffer[19] and 0xFF}"
                            val totalLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                            val protoName = when(protocol) { 6 -> "TCP" 17 -> "UDP" 1 -> "ICMP" else -> "??" }
                            LogManager.addLog("🔍 Pkt #${packetCount}: IP${if(version==4) "v4" else "v6"} $protoName $srcIP → $dstIP len=$totalLen (read:${readTime}ms)")
                        }
                        
                        // Processar o pacote
                        processPacket(buffer, length, outputStream)
                    } else if (length == -1) {
                        LogManager.addLog("⚠️ read retornou -1 (EOF)")
                        break
                    }
                } catch (e: InterruptedException) {
                    LogManager.addLog("Thread interrompida")
                    break
                } catch (e: Exception) {
                    errorCount++
                    if (errorCount <= 10) {
                        LogManager.addLog("❌ Erro #${errorCount} no loop: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    if (errorCount > 20) {
                        LogManager.addLog("❌ Muitos erros (${errorCount}), parando loop")
                        break
                    }
                }
            }
            
            LogManager.addLog("📊 FINAL: ${packetCount} pacotes | SOCKS OK: ${socksOk} | SOCKS FAIL: ${socksFail} | Erros: ${errorCount}")
        } catch (e: Exception) {
            LogManager.addLog("❌ FATAL: ${e.javaClass.simpleName}: ${e.message}")
            LogManager.addLog("Stack: ${e.stackTraceToString().take(300)}")
        }
    }

    private fun processPacket(data: ByteArray, len: Int, outStream: FileOutputStream) {
        if (len < 20) return
        
        try {
            val version = (data[0].toInt() shr 4) and 0x0F
            if (version != 4) return // Só IPv4
            
            val protocol = data[9].toInt() and 0xFF
            if (protocol != 6 && protocol != 17) return // Só TCP e UDP
            
            val dstIP = ByteArray(4)
            System.arraycopy(data, 16, dstIP, 0, 4)
            val dstPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
            val dstStr = "${dstIP[0] and 0xFF}.${dstIP[1] and 0xFF}.${dstIP[2] and 0xFF}.${dstIP[3] and 0xFF}"
            
            // Log para os primeiros destinos diferentes
            if (packetCount <= 10) {
                LogManager.addLog("  🎯 Destino: $dstStr:$dstPort (${if(protocol==6) "TCP" else "UDP"})")
            }
            
            // Conectar ao SOCKS5
            val sock = Socket()
            sock.soTimeout = 10000
            sock.connect(InetSocketAddress("127.0.0.1", XrayCoreService.SOCKS_PORT), 5000)
            
            // PROTEGER o socket
            val protectResult = protect(sock)
            if (packetCount <= 3) {
                LogManager.addLog("  🔒 protect() = $protectResult")
            }
            
            val sockIn = sock.getInputStream()
            val sockOut = sock.getOutputStream()
            
            // SOCKS5 handshake
            sockOut.write(byteArrayOf(5, 1, 0))
            sockOut.flush()
            val hsResp = ByteArray(2)
            val hsLen = sockIn.read(hsResp)
            
            if (hsLen != 2 || hsResp[1] != 0.toByte()) {
                if (packetCount <= 5) {
                    LogManager.addLog("  ⚠️ SOCKS handshake falhou: len=$hsLen resp=${hsResp.toHex()}")
                }
                sock.close()
                socksFail++
                return
            }
            
            // SOCKS5 CONNECT
            val cmd = if (protocol == 6) 1.toByte() else 3.toByte() // TCP=CONNECT, UDP=UDP_ASSOCIATE
            val baos = java.io.ByteArrayOutputStream()
            baos.write(5) // VER
            baos.write(cmd.toInt()) // CMD
            baos.write(0) // RSV
            baos.write(1) // ATYP = IPv4
            baos.write(dstIP)
            baos.write((dstPort shr 8) and 0xFF)
            baos.write(dstPort and 0xFF)
            sockOut.write(baos.toByteArray())
            sockOut.flush()
            
            val connResp = ByteArray(10)
            val crLen = sockIn.read(connResp)
            
            if (crLen < 10 || connResp[1] != 0.toByte()) {
                val respCode = if (crLen >= 2) connResp[1].toInt() else -1
                if (packetCount <= 5 || socksFail < 5) {
                    LogManager.addLog("  ⚠️ SOCKS CONNECT falhou: dst=$dstStr:$dstPort code=$respCode")
                }
                sock.close()
                socksFail++
                return
            }
            
            // Sucesso - enviar dados
            sockOut.write(data, 0, len)
            sockOut.flush()
            
            // Ler resposta
            val response = ByteArray(32767)
            val rLen = sockIn.read(response)
            if (rLen > 0) {
                synchronized(outStream) {
                    outStream.write(response, 0, rLen)
                    outStream.flush()
                }
            }
            
            sock.close()
            socksOk++
            
            if (socksOk == 1) {
                LogManager.addLog("  ✅ Primeira conexão SOCKS5 bem sucedida!")
                LogManager.addLog("     Destino: $dstStr:$dstPort")
                LogManager.addLog("     Resposta: $rLen bytes")
            }
            
        } catch (e: Exception) {
            socksFail++
            if (socksFail <= 5) {
                LogManager.addLog("  ❌ SOCKS error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // Extensão para debug
    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    override fun onDestroy() {
        LogManager.addLog(">>> onDestroy() chamado")
        LogManager.addLog("Stats finais: ${packetCount} pkts, SOCKS: ${socksOk}/${socksFail}, Erros: ${errorCount}")
        running = false
        vpnThread?.interrupt()
        xray?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {
            LogManager.addLog("Erro ao fechar VPN: ${e.message}")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        LogManager.addLog("✅ VPN completamente parada")
        super.onDestroy()
    }
}
