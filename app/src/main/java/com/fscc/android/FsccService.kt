package com.fscc.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FsccService : Service() {

    companion object {
        const val ACTION_START = "com.fscc.android.START"
        const val ACTION_STOP = "com.fscc.android.STOP"
        const val EXTRA_IFACE = "iface"
        const val EXTRA_IP = "ip"
        const val EXTRA_STRICT_OFFLINE = "strict_offline"
        private const val DNS_PORT = 5354
        private const val HTTP_PORT = 8095
        private const val CHANNEL_ID = "fscc_channel"
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var udpDnsSocket: DatagramSocket? = null
    private var tcpDnsServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null
    private var currentIface: String = ""
    private var currentIp: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
            }
            ACTION_START -> {
                val iface = intent.getStringExtra(EXTRA_IFACE).orEmpty()
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                val strict = intent.getBooleanExtra(EXTRA_STRICT_OFFLINE, true)
                startForegroundNotif("FSCC actif sur $iface ($ip)")
                if (!running.get()) {
                    startAll(iface, ip, strict)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAll()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startForegroundNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "FSCC Server", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("FSCC Switch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun startAll(iface: String, ip: String, strictOffline: Boolean) {
        if (!iface.matches(Regex("^[a-zA-Z0-9._-]+$")) ||
            !ip.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$"))
        ) {
            FsccBus.log("ERREUR : Interface ou IP invalide ($iface / $ip)")
            stopSelf()
            return
        }

        val ipBytes = ip.split(".").map { it.toInt().toByte() }.toByteArray()
        currentIface = iface
        currentIp = ip
        running.set(true)

        pool.execute {
            try {
                udpDnsSocket = DatagramSocket(DNS_PORT).apply { reuseAddress = true }
                httpServer = ServerSocket(HTTP_PORT).apply { reuseAddress = true }
                tcpDnsServer = ServerSocket(DNS_PORT).apply { reuseAddress = true }

                val ok = applyIptables(iface, ip, strictOffline)
                if (!ok) {
                    FsccBus.log("ERREUR : Accès root (su) ou iptables refusé !")
                    stopAll()
                    stopSelf()
                    return@execute
                }

                FsccBus.isRunning = true
                FsccBus.activeIface = iface
                FsccBus.activeIp = ip
                FsccBus.notifyState()
                FsccBus.log("FSCC démarré sur $iface ($ip) | DNS:$DNS_PORT HTTP:$HTTP_PORT")

                pool.execute { runUdpDnsLoop(ip, ipBytes) }
                pool.execute { runTcpDnsLoop(ip, ipBytes) }
                pool.execute { runHttpLoop() }
            } catch (e: Exception) {
                FsccBus.log("ERREUR démarrage : ${e.message}")
                stopAll()
                stopSelf()
            }
        }
    }

    private fun stopAll() {
        running.set(false)
        try { udpDnsSocket?.close() } catch (_: Exception) {}
        try { tcpDnsServer?.close() } catch (_: Exception) {}
        try { httpServer?.close() } catch (_: Exception) {}
        udpDnsSocket = null
        tcpDnsServer = null
        httpServer = null

        if (currentIface.isNotEmpty()) {
            removeIptables()
            FsccBus.log("FSCC arrêté (règles iptables nettoyées)")
        }
        currentIface = ""
        currentIp = ""
        FsccBus.isRunning = false
        FsccBus.activeIface = ""
        FsccBus.activeIp = ""
        FsccBus.notifyState()
    }

    private fun runUdpDnsLoop(localIp: String, ipBytes: ByteArray) {
        val buf = ByteArray(1500)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpDnsSocket?.receive(pkt) ?: break
                val req = pkt.data.copyOf(pkt.length)
                val clientIp = pkt.address.hostAddress ?: "?"
                val res = buildDnsResponse(req, clientIp, localIp, ipBytes) ?: continue
                udpDnsSocket?.send(DatagramPacket(res, res.size, pkt.address, pkt.port))
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun runTcpDnsLoop(localIp: String, ipBytes: ByteArray) {
        while (running.get()) {
            try {
                val sock = tcpDnsServer?.accept() ?: break
                pool.execute {
                    sock.use { s ->
                        s.soTimeout = 3000
                        val din = DataInputStream(s.getInputStream())
                        val len = din.readUnsignedShort()
                        if (len in 12..4096) {
                            val req = ByteArray(len)
                            din.readFully(req)
                            val clientIp = s.inetAddress.hostAddress ?: "?"
                            val res = buildDnsResponse(req, clientIp, localIp, ipBytes)
                            if (res != null) {
                                val dout = DataOutputStream(s.getOutputStream())
                                dout.writeShort(res.size)
                                dout.write(res)
                                dout.flush()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun runHttpLoop() {
        while (running.get()) {
            try {
                val sock = httpServer?.accept() ?: break
                pool.execute { handleHttp(sock) }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleHttp(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val firstLine = reader.readLine() ?: ""
                val path = firstLine.split(" ").getOrNull(1) ?: "/"
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                FsccBus.log("[HTTP-CHECK] Switch (${s.inetAddress.hostAddress}) -> $path")
                val resp = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "X-Organization: Nintendo\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: close\r\n\r\nok"
                ).toByteArray(Charsets.US_ASCII)
                s.getOutputStream().write(resp)
                s.getOutputStream().flush()
            } catch (_: Exception) {}
        }
    }

    private fun buildDnsResponse(req: ByteArray, clientIp: String, localIp: String, ipBytes: ByteArray): ByteArray? {
        if (req.size < 13) return null
        var offset = 12
        val parts = mutableListOf<String>()
        while (offset < req.size) {
            val len = req[offset].toInt() and 0xFF
            if (len == 0) {
                offset += 1
                break
            }
            if ((len and 0xC0) != 0 || offset + 1 + len > req.size) return null
            parts.add(String(req, offset + 1, len, Charsets.UTF_8))
            offset += 1 + len
        }
        if (offset + 4 > req.size) return null
        val qType = ((req[offset].toInt() and 0xFF) shl 8) or (req[offset + 1].toInt() and 0xFF)
        val endOffset = offset + 4
        val domain = parts.joinToString(".").lowercase()
        val question = req.copyOfRange(12, endOffset)
        val txId = byteArrayOf(req[0], req[1])

        return if (domain.contains("ctest") || domain.contains("conntest")) {
            if (qType == 1) {
                FsccBus.log("[AUTORISÉ] $domain ($clientIp) -> $localIp")
                txId + byteArrayOf(
                    0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
                ) + question + byteArrayOf(
                    0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01,
                    0x00, 0x00, 0x00, 0x3C, 0x00, 0x04
                ) + ipBytes
            } else {
                txId + byteArrayOf(
                    0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                ) + question
            }
        } else {
            FsccBus.log("[BLOQUÉ] $domain ($clientIp) -> NXDOMAIN")
            txId + byteArrayOf(
                0x81.toByte(), 0x83.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            ) + question
        }
    }

    private fun runSu(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val rc = p.waitFor()
            if (rc != 0) {
                val err = p.errorStream.bufferedReader().readText().trim()
                if (err.isNotEmpty()) FsccBus.log("su: $err")
            }
            rc == 0
        } catch (e: Exception) {
            FsccBus.log("su exception: ${e.message}")
            false
        }
    }

    private fun applyIptables(iface: String, ip: String, strictOffline: Boolean): Boolean {
        removeIptables()
        val sb = StringBuilder()
        sb.append("iptables -w -t nat -N FSCC_NAT && ")
        sb.append("iptables -w -t nat -A FSCC_NAT -i $iface -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT && ")
        sb.append("iptables -w -t nat -A FSCC_NAT -i $iface -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT && ")
        sb.append("iptables -w -t nat -A FSCC_NAT -i $iface -p tcp -d $ip --dport 80 -j REDIRECT --to-ports $HTTP_PORT && ")
        sb.append("iptables -w -t nat -I PREROUTING 1 -j FSCC_NAT && ")

        sb.append("iptables -w -N FSCC_IN && ")
        sb.append("iptables -w -A FSCC_IN -i $iface -p udp --dport $DNS_PORT -j ACCEPT && ")
        sb.append("iptables -w -A FSCC_IN -i $iface -p tcp --dport $DNS_PORT -j ACCEPT && ")
        sb.append("iptables -w -A FSCC_IN -i $iface -p tcp --dport $HTTP_PORT -j ACCEPT && ")
        sb.append("iptables -w -I INPUT 1 -j FSCC_IN")

        if (strictOffline) {
            sb.append(" && iptables -w -N FSCC_FWD")
            sb.append(" && iptables -w -A FSCC_FWD -i $iface -j DROP")
            sb.append(" && iptables -w -I FORWARD 1 -j FSCC_FWD")
            sb.append(" && (ip6tables -w -N FSCC_FWD6 2>/dev/null || true)")
            sb.append(" && (ip6tables -w -A FSCC_FWD6 -i $iface -j DROP 2>/dev/null || true)")
            sb.append(" && (ip6tables -w -I FORWARD 1 -j FSCC_FWD6 2>/dev/null || true)")
            sb.append(" && (ip6tables -w -N FSCC_IN6 2>/dev/null || true)")
            sb.append(" && (ip6tables -w -A FSCC_IN6 -i $iface -p udp --dport 53 -j REJECT 2>/dev/null || true)")
            sb.append(" && (ip6tables -w -A FSCC_IN6 -i $iface -p tcp --dport 53 -j REJECT 2>/dev/null || true)")
            sb.append(" && (ip6tables -w -I INPUT 1 -j FSCC_IN6 2>/dev/null || true)")
        }
        return runSu(sb.toString())
    }

    private fun removeIptables() {
        val cleanup = listOf(
            "while iptables -w -t nat -D PREROUTING -j FSCC_NAT 2>/dev/null; do :; done",
            "iptables -w -t nat -F FSCC_NAT 2>/dev/null; iptables -w -t nat -X FSCC_NAT 2>/dev/null",
            "while iptables -w -D INPUT -j FSCC_IN 2>/dev/null; do :; done",
            "iptables -w -F FSCC_IN 2>/dev/null; iptables -w -X FSCC_IN 2>/dev/null",
            "while iptables -w -D FORWARD -j FSCC_FWD 2>/dev/null; do :; done",
            "iptables -w -F FSCC_FWD 2>/dev/null; iptables -w -X FSCC_FWD 2>/dev/null",
            "while ip6tables -w -D FORWARD -j FSCC_FWD6 2>/dev/null; do :; done",
            "ip6tables -w -F FSCC_FWD6 2>/dev/null; ip6tables -w -X FSCC_FWD6 2>/dev/null",
            "while ip6tables -w -D INPUT -j FSCC_IN6 2>/dev/null; do :; done",
            "ip6tables -w -F FSCC_IN6 2>/dev/null; ip6tables -w -X FSCC_IN6 2>/dev/null",
            "true"
        ).joinToString("; ")
        runSu(cleanup)
    }
}
