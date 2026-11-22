package com.fscc.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FsccService : Service() {

    companion object {
        const val ACTION_START = "com.fscc.android.START"
        const val ACTION_STOP = "com.fscc.android.STOP"
        const val ACTION_SET_MODE = "com.fscc.android.SET_MODE"
        const val ACTION_CYCLE_MODE = "com.fscc.android.CYCLE_MODE"
        const val EXTRA_IFACE = "iface"
        const val EXTRA_IP = "ip"
        const val EXTRA_STRICT_OFFLINE = "strict_offline"
        const val EXTRA_MODE = "mode"
        const val EXTRA_AUTO_HOTSPOT = "auto_hotspot"
        private const val DNS_PORT = 5354
        private const val HTTP_PORT = 8095
        private const val CHANNEL_ID = "fscc_channel"

        private val BLOCKED_SUFFIXES = listOf(
            "nintendo.com", "nintendo.net", "nintendo.jp", "nintendo.co.jp",
            "nintendo.co.uk", "nintendo-europe.com", "nintendowifi.net",
            "nintendo.es", "nintendo.co.kr", "nintendo.tw", "nintendo.com.hk",
            "nintendo.com.au", "nintendo.co.nz", "nintendo.at", "nintendo.be",
            "nintendods.cz", "nintendo.dk", "nintendo.de", "nintendo.fi",
            "nintendo.fr", "nintendo.gr", "nintendo.hu", "nintendo.it",
            "nintendo.nl", "nintendo.no", "nintendo.pt", "nintendo.ru",
            "nintendo.co.za", "nintendo.se", "nintendo.ch", "nintendo.pl",
            "nintendoswitch.com", "nintendoswitch.com.cn", "nintendoswitch.cn",
            "nintendocdn.com", "nintendowifi.com", "nintendods.com",
            "nintendogamecube.com", "nintendo.org", "nintendo.biz",
            "nintendo.info", "nintendo.tv", "nintendo.lu", "nintendo-europe-sales.com",
            "nega-lp1-upload.s3.us-east-1.amazonaws.com",
            "scsi-upload-lp1.s3.us-west-2.amazonaws.com",
            "google-analytics.com", "googletagmanager.com",
            "dns.google", "dns.google.com", "cloudflare-dns.com",
            "one.one.one.one", "mozilla.cloudflare-dns.com",
            "dns.quad9.net", "doh.opendns.com",
            "dns.adguard-dns.com", "dns.nextdns.io"
        )
    }

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val modeLock = Any()
    private var udpDnsSocket: DatagramSocket? = null
    private var tcpDnsServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentIface: String = ""
    private var currentIp: String = ""
    @Volatile private var currentMode: FsccMode = FsccMode.STRICT
    @Volatile private var manageHotspot: Boolean = false
    @Volatile private var hotspotStartedByUs: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
            }
            ACTION_SET_MODE -> {
                if (running.get()) {
                    val newMode = FsccMode.fromPref(intent.getStringExtra(EXTRA_MODE), true)
                    pool.execute { switchModeLive(newMode) }
                }
            }
            ACTION_CYCLE_MODE -> {
                if (running.get()) {
                    val nextMode = when (currentMode) {
                        FsccMode.STRICT -> FsccMode.DNS90
                        FsccMode.DNS90 -> FsccMode.WHITELIST
                        FsccMode.WHITELIST -> FsccMode.STRICT
                    }
                    pool.execute { switchModeLive(nextMode) }
                }
            }
            ACTION_START -> {
                loadListsFromPrefs()
                val iface = intent.getStringExtra(EXTRA_IFACE).orEmpty()
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                val prefs = getSharedPreferences("fscc_prefs", MODE_PRIVATE)
                val modeStr = intent.getStringExtra(EXTRA_MODE) ?: prefs.getString("app_mode", null)
                val strictFallback = intent.getBooleanExtra(EXTRA_STRICT_OFFLINE, true)
                val startMode = FsccMode.fromPref(modeStr, strictFallback)
                val autoHotspot = intent.getBooleanExtra(EXTRA_AUTO_HOTSPOT, false)
                val label = if (iface.isNotEmpty()) "${startMode.label} sur $iface ($ip)" else "${startMode.label} démarrage..."
                startForegroundNotif(label)
                if (!running.get()) {
                    startAll(iface, ip, startMode, autoHotspot)
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

    private fun loadListsFromPrefs() {
        val prefs = getSharedPreferences("fscc_prefs", MODE_PRIVATE)
        val wlRaw = prefs.getString("whitelist_raw", FsccBus.DEFAULT_WHITELIST) ?: FsccBus.DEFAULT_WHITELIST
        val extraRaw = prefs.getString("extra_blocked_raw", "") ?: ""
        FsccBus.whitelistDomains = FsccBus.parseDomainList(wlRaw)
        FsccBus.extraBlockedDomains = FsccBus.parseDomainList(extraRaw)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "FSCC Server", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cycleIntent = Intent(this, FsccService::class.java).apply { action = ACTION_CYCLE_MODE }
        val cyclePi = PendingIntent.getService(
            this, 2, cycleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cycleAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_popup_sync),
            "Mode ⇄",
            cyclePi
        ).build()

        val stopIntent = Intent(this, FsccService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Arrêter",
            stopPi
        ).build()

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FSCC Switch [${currentMode.label}]")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(cycleAction)
            .addAction(stopAction)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotif(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun refreshNotif(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1, buildNotification(text))
        }
    }

    private fun syncTile() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, FsccTileService::class.java))
        }
    }

    private fun findHotspotIface(): Pair<String, String>? {
        val priority = listOf("wlan2", "swlan0", "ap0", "softap0", "wlan1")
        val all = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        for (name in priority) {
            val ni = all.firstOrNull { it.name == name && it.isUp && !it.isLoopback } ?: continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    return name to ip
                }
            }
        }
        return null
    }

    private fun isIfaceUp(iface: String): Boolean {
        return try {
            val ni = NetworkInterface.getByName(iface) ?: return false
            ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
        } catch (_: Exception) {
            false
        }
    }

    private fun runHotspotCtl(action: String): Boolean {
        val apkPath = applicationInfo.sourceDir
        val cmd = "CLASSPATH=" + "\"" + apkPath + "\"" + " app_process /system/bin com.fscc.android.HotspotCtl " + action
        return runSu(cmd)
    }

    private fun startAll(ifaceParam: String, ipParam: String, startMode: FsccMode, autoHotspot: Boolean) {
        running.set(true)
        currentMode = startMode
        manageHotspot = autoHotspot
        hotspotStartedByUs = false
        FsccBus.mode = startMode
        FsccBus.resetStats()

        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fscc:server").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }

        pool.execute {
            try {
                runSu("touch /dev/fscc_allowed")
                var iface = ifaceParam
                var ip = ipParam

                if (autoHotspot) {
                    val existing = findHotspotIface()
                    if (existing != null) {
                        iface = existing.first
                        ip = existing.second
                        hotspotStartedByUs = true
                    } else {
                        FsccBus.log("Allumage automatique du hotspot...")
                        if (!runHotspotCtl("start")) {
                            FsccBus.log("ERREUR : Échec allumage hotspot via root")
                            stopAll()
                            stopSelf()
                            return@execute
                        }
                        hotspotStartedByUs = true
                        var found: Pair<String, String>? = null
                        for (idx in 0 until 40) {
                            if (!running.get()) break
                            found = findHotspotIface()
                            if (found != null) break
                            Thread.sleep(250)
                        }
                        if (!running.get()) return@execute
                        if (found == null) {
                            FsccBus.log("ERREUR : Hotspot actif mais aucune interface IPv4 détectée")
                            stopAll()
                            stopSelf()
                            return@execute
                        }
                        iface = found.first
                        ip = found.second
                    }
                }

                if (!iface.matches(Regex("^[a-zA-Z0-9._-]+$")) ||
                    !ip.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$"))
                ) {
                    FsccBus.log("ERREUR : Interface ou IP invalide ($iface / $ip)")
                    stopAll()
                    stopSelf()
                    return@execute
                }

                val ipBytes = ip.split(".").map { it.toInt().toByte() }.toByteArray()
                currentIface = iface
                currentIp = ip

                udpDnsSocket = DatagramSocket(DNS_PORT).apply { reuseAddress = true }
                httpServer = ServerSocket(HTTP_PORT).apply { reuseAddress = true }
                tcpDnsServer = ServerSocket(DNS_PORT).apply { reuseAddress = true }

                val ok = applyIptables(iface, ip, startMode == FsccMode.STRICT)
                if (!ok) {
                    FsccBus.log("ERREUR : Accès root (su) ou iptables refusé !")
                    stopAll()
                    stopSelf()
                    return@execute
                }

                startForegroundNotif("$iface ($ip) • OK:0 | Bloq:0")
                FsccBus.isRunning = true
                FsccBus.activeIface = iface
                FsccBus.activeIp = ip
                FsccBus.notifyState()
                syncTile()
                FsccBus.log("Démarré [${startMode.label}] sur $iface ($ip) | DNS:$DNS_PORT HTTP:$HTTP_PORT")

                pool.execute { runUdpDnsLoop(ip, ipBytes) }
                pool.execute { runTcpDnsLoop(ip, ipBytes) }
                pool.execute { runHttpLoop() }
                pool.execute { runWatchdog(iface, ip) }
            } catch (e: Exception) {
                FsccBus.log("ERREUR démarrage : ${e.message}")
                stopAll()
                stopSelf()
            }
        }
    }

    private fun switchModeLive(newMode: FsccMode) {
        synchronized(modeLock) {
            if (!running.get() || currentIface.isEmpty() || newMode == currentMode) return
            val iface = currentIface
            val strict = (newMode == FsccMode.STRICT)
            if (strict) {
                currentMode = newMode
            }
            val cmd = if (strict) {
                "iptables -w -F FSCC_FWD && " +
                "iptables -w -A FSCC_FWD -i $iface -j DROP && " +
                "iptables -w -A FSCC_FWD -o $iface -j DROP"
            } else {
                "iptables -w -F FSCC_FWD && " +
                "iptables -w -A FSCC_FWD -i $iface -p tcp --dport 853 -j DROP && " +
                "iptables -w -A FSCC_FWD -i $iface -p udp --dport 853 -j DROP && " +
                "iptables -w -A FSCC_FWD -i $iface -j ACCEPT && " +
                "iptables -w -A FSCC_FWD -o $iface -j ACCEPT"
            }
            if (runSu(cmd)) {
                currentMode = newMode
                FsccBus.mode = newMode
                getSharedPreferences("fscc_prefs", MODE_PRIVATE).edit()
                    .putString("app_mode", newMode.prefValue)
                    .putBoolean("strict_offline", strict)
                    .apply()
                refreshNotif("$iface ($currentIp) • OK:${FsccBus.allowedCount} | Bloq:${FsccBus.blockedCount}")
                FsccBus.log("Bascule à chaud -> [${newMode.label}] (hotspot conservé)")
                FsccBus.notifyState()
                syncTile()
            } else {
                FsccBus.log("ERREUR : Échec bascule à chaud vers ${newMode.label}")
            }
        }
    }

    private fun runWatchdog(iface: String, ip: String) {
        var lastAllowed = -1
        var lastBlocked = -1
        var lastMode = currentMode
        while (running.get()) {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                break
            }
            if (!running.get()) break
            if (!isIfaceUp(iface)) {
                FsccBus.log("Hotspot coupé depuis le système -> nettoyage FSCC")
                stopAll()
                stopSelf()
                break
            }
            val a = FsccBus.allowedCount
            val b = FsccBus.blockedCount
            val m = currentMode
            if (a != lastAllowed || b != lastBlocked || m != lastMode) {
                lastAllowed = a
                lastBlocked = b
                lastMode = m
                refreshNotif("$iface ($ip) • OK:$a | Bloq:$b")
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

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }

        val hadIface = currentIface.isNotEmpty()
        currentIface = ""
        currentIp = ""
        if (hadIface) {
            removeIptables()
            FsccBus.log("Arrêté (règles iptables nettoyées)")
        }

        val shouldStopHs = manageHotspot && hotspotStartedByUs
        manageHotspot = false
        hotspotStartedByUs = false
        if (shouldStopHs && findHotspotIface() != null) {
            if (runHotspotCtl("stop")) {
                for (idx in 0 until 15) {
                    if (findHotspotIface() == null) break
                    Thread.sleep(200)
                }
                FsccBus.log("Hotspot coupé automatiquement")
            } else {
                FsccBus.log("Attention : échec coupure automatique du hotspot")
            }
        }
        if (hadIface || shouldStopHs) {
            runSu("rm -f /dev/fscc_allowed")
        }

        FsccBus.isRunning = false
        FsccBus.activeIface = ""
        FsccBus.activeIp = ""
        FsccBus.notifyState()
        syncTile()
    }

    private fun runUdpDnsLoop(localIp: String, ipBytes: ByteArray) {
        val buf = ByteArray(1500)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpDnsSocket?.receive(pkt) ?: break
                val req = pkt.data.copyOf(pkt.length)
                val clientIp = pkt.address.hostAddress ?: "?"
                val addr = pkt.address
                val port = pkt.port
                if (currentMode == FsccMode.STRICT) {
                    val res = buildDnsResponse(req, clientIp, localIp, ipBytes) ?: continue
                    udpDnsSocket?.send(DatagramPacket(res, res.size, addr, port))
                } else {
                    pool.execute {
                        val res = buildDnsResponse(req, clientIp, localIp, ipBytes) ?: return@execute
                        try {
                            udpDnsSocket?.send(DatagramPacket(res, res.size, addr, port))
                        } catch (_: Exception) {}
                    }
                }
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
                val isHead = firstLine.startsWith("HEAD ")
                var host = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("host:")) {
                        host = line.substringAfter(":").trim().lowercase().substringBefore(":")
                    }
                }
                val clientIp = s.inetAddress.hostAddress ?: "?"
                FsccBus.incHttp(clientIp)
                FsccBus.log("[HTTP-CHECK] $host$path ($clientIp)")

                val body: String
                val ctype: String
                val org: String
                if (host.contains("conntest")) {
                    body = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\"><html><head><title>HTML Page</title></head><body bgcolor=\"#FFFFFF\">This is test.html page</body></html>"
                    ctype = "text/html"
                    org = "Nintendo"
                } else if (host.endsWith("90dns.test")) {
                    body = "You are successfully connected to 90DNS. Happy hacking!"
                    ctype = "text/html"
                    org = "LavaTech"
                } else {
                    body = "ok"
                    ctype = "text/plain"
                    org = "Nintendo"
                }
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val header = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $ctype\r\n" +
                    "X-Organization: $org\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
                s.getOutputStream().write(header)
                if (!isHead) {
                    s.getOutputStream().write(bodyBytes)
                }
                s.getOutputStream().flush()
            } catch (_: Exception) {}
        }
    }

    private fun isNintendoBlocked(domain: String): Boolean {
        if (domain.contains("nintendo")) return true
        if (BLOCKED_SUFFIXES.any { s -> domain == s || domain.endsWith(".$s") }) return true
        return FsccBus.extraBlockedDomains.any { s -> domain == s || domain.endsWith(".$s") }
    }

    private fun isWhitelisted(domain: String): Boolean {
        return FsccBus.whitelistDomains.any { s -> domain == s || domain.endsWith(".$s") }
    }

    private fun buildBlockedZeroResponse(
        txId: ByteArray,
        question: ByteArray,
        qType: Int,
        domain: String,
        clientIp: String,
        tag: String
    ): ByteArray {
        return when (qType) {
            1 -> {
                FsccBus.log("[$tag] $domain ($clientIp) -> 0.0.0.0")
                txId + byteArrayOf(
                    0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
                ) + question + byteArrayOf(
                    0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01,
                    0x00, 0x00, 0x00, 0x3C, 0x00, 0x04,
                    0x00, 0x00, 0x00, 0x00
                )
            }
            28 -> {
                FsccBus.log("[$tag] $domain ($clientIp) -> ::")
                txId + byteArrayOf(
                    0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
                ) + question + byteArrayOf(
                    0xC0.toByte(), 0x0C, 0x00, 0x1C, 0x00, 0x01,
                    0x00, 0x00, 0x00, 0x3C, 0x00, 0x10
                ) + ByteArray(16)
            }
            else -> {
                FsccBus.log("[$tag] $domain ($clientIp) [type=$qType] -> vide")
                txId + byteArrayOf(
                    0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                ) + question
            }
        }
    }

    private fun forwardDnsUpstream(req: ByteArray): ByteArray? {
        for (upstream in listOf("1.1.1.1", "8.8.8.8")) {
            try {
                DatagramSocket().use { ds ->
                    ds.soTimeout = 2500
                    val addr = InetAddress.getByName(upstream)
                    ds.send(DatagramPacket(req, req.size, addr, 53))
                    val buf = ByteArray(1500)
                    val respPkt = DatagramPacket(buf, buf.size)
                    ds.receive(respPkt)
                    return respPkt.data.copyOf(respPkt.length)
                }
            } catch (_: Exception) {}
        }
        return null
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

        val isTestDomain = domain.contains("ctest") || domain.contains("conntest") ||
            domain == "90dns.test" || domain.endsWith(".90dns.test")

        if (isTestDomain) {
            FsccBus.recordDomain(domain, "CHECK")
            return if (qType == 1) {
                FsccBus.incAllowed(clientIp)
                FsccBus.log("[CHECK-OK] $domain ($clientIp) -> $localIp")
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
        }

        val modeNow = currentMode
        if (modeNow == FsccMode.STRICT) {
            FsccBus.incBlocked(clientIp)
            FsccBus.recordDomain(domain, "BLOQUÉ")
            FsccBus.log("[BLOQUÉ] $domain ($clientIp) -> NXDOMAIN")
            return txId + byteArrayOf(
                0x81.toByte(), 0x83.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            ) + question
        }

        if (isNintendoBlocked(domain)) {
            FsccBus.incBlocked(clientIp)
            FsccBus.recordDomain(domain, "BLOQUÉ")
            return buildBlockedZeroResponse(txId, question, qType, domain, clientIp, "90DNS-BLOQUÉ")
        }

        if (modeNow == FsccMode.WHITELIST && !isWhitelisted(domain)) {
            FsccBus.incBlocked(clientIp)
            FsccBus.recordDomain(domain, "WL-BLOQUÉ")
            return buildBlockedZeroResponse(txId, question, qType, domain, clientIp, "WL-BLOQUÉ")
        }

        if (qType == 28 || qType == 65) {
            return txId + byteArrayOf(
                0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            ) + question
        }

        val upstream = forwardDnsUpstream(req)
        return if (upstream != null) {
            FsccBus.incAllowed(clientIp)
            val isNew = FsccBus.recordDomain(domain, "PASS")
            val tag = if (modeNow == FsccMode.WHITELIST) "WL-PASS" else if (isNew) "90DNS-NOUVEAU" else "90DNS-PASS"
            FsccBus.log("[$tag] $domain ($clientIp)")
            upstream
        } else {
            txId + byteArrayOf(
                0x81.toByte(), 0x82.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
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

        sb.append(" && iptables -w -N FSCC_FWD")
        if (strictOffline) {
            sb.append(" && iptables -w -A FSCC_FWD -i $iface -j DROP")
            sb.append(" && iptables -w -A FSCC_FWD -o $iface -j DROP")
        } else {
            sb.append(" && iptables -w -A FSCC_FWD -i $iface -p tcp --dport 853 -j DROP")
            sb.append(" && iptables -w -A FSCC_FWD -i $iface -p udp --dport 853 -j DROP")
            sb.append(" && iptables -w -A FSCC_FWD -i $iface -j ACCEPT")
            sb.append(" && iptables -w -A FSCC_FWD -o $iface -j ACCEPT")
        }
        sb.append(" && iptables -w -I FORWARD 1 -j FSCC_FWD")

        sb.append(" && (ip6tables -w -N FSCC_FWD6 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -A FSCC_FWD6 -i $iface -j DROP 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -A FSCC_FWD6 -o $iface -j DROP 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -I FORWARD 1 -j FSCC_FWD6 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -N FSCC_IN6 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -A FSCC_IN6 -i $iface -p udp --dport 53 -j REJECT 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -A FSCC_IN6 -i $iface -p tcp --dport 53 -j REJECT 2>/dev/null || true)")
        sb.append(" && (ip6tables -w -I INPUT 1 -j FSCC_IN6 2>/dev/null || true)")
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
