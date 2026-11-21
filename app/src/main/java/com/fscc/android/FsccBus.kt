package com.fscc.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FsccMode(val prefValue: String, val label: String) {
    STRICT("strict", "FSCC STRICT"),
    DNS90("90dns", "90DNS"),
    WHITELIST("whitelist", "WHITELIST");

    companion object {
        fun fromPref(value: String?, fallbackStrict: Boolean): FsccMode {
            return values().firstOrNull { it.prefValue == value }
                ?: if (fallbackStrict) STRICT else DNS90
        }
    }
}

object FsccBus {
    const val DEFAULT_WHITELIST =
        "youtube.com, youtu.be, googlevideo.com, ytimg.com, ggpht.com, googleusercontent.com, gstatic.com, github.com, githubusercontent.com, hb-app.store"

    @Volatile var isRunning: Boolean = false
    @Volatile var activeIface: String = ""
    @Volatile var activeIp: String = ""
    @Volatile var mode: FsccMode = FsccMode.STRICT
    val modeLabel: String get() = mode.label
    @Volatile var allowedCount: Int = 0
    @Volatile var blockedCount: Int = 0
    @Volatile var httpCount: Int = 0
    @Volatile var lastClientIp: String = ""
    @Volatile var whitelistDomains: Set<String> = parseDomainList(DEFAULT_WHITELIST)
    @Volatile var extraBlockedDomains: Set<String> = emptySet()

    private val logs = ArrayDeque<String>(250)
    private val seenDomains = LinkedHashMap<String, String>(250)
    private var listener: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun parseDomainList(raw: String): Set<String> {
        return raw.split(",", "\n", ";", " ")
            .map { it.trim().lowercase().removePrefix("*.").removePrefix(".") }
            .filter { it.isNotEmpty() && it.contains(".") }
            .toSet()
    }

    @Synchronized
    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    @Synchronized
    fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        if (logs.size >= 250) logs.removeFirst()
        logs.addLast(line)
        listener?.invoke()
    }

    @Synchronized
    fun recordDomain(domain: String, status: String): Boolean {
        val existed = seenDomains.containsKey(domain)
        if (!existed && seenDomains.size >= 250) {
            val firstKey = seenDomains.keys.firstOrNull()
            if (firstKey != null) seenDomains.remove(firstKey)
        }
        seenDomains[domain] = status
        return !existed
    }

    @Synchronized
    fun seenDomainsSnapshot(): List<Pair<String, String>> =
        seenDomains.entries.map { it.key to it.value }

    @Synchronized
    fun seenDomainsCount(): Int = seenDomains.size

    @Synchronized
    fun resetStats() {
        allowedCount = 0
        blockedCount = 0
        httpCount = 0
        lastClientIp = ""
        seenDomains.clear()
        listener?.invoke()
    }

    @Synchronized
    fun incAllowed(clientIp: String) {
        allowedCount++
        lastClientIp = clientIp
    }

    @Synchronized
    fun incBlocked(clientIp: String) {
        blockedCount++
        if (lastClientIp.isEmpty()) lastClientIp = clientIp
    }

    @Synchronized
    fun incHttp(clientIp: String) {
        httpCount++
        lastClientIp = clientIp
    }

    @Synchronized
    fun clear() {
        logs.clear()
        allowedCount = 0
        blockedCount = 0
        httpCount = 0
        lastClientIp = ""
        seenDomains.clear()
        listener?.invoke()
    }

    @Synchronized
    fun snapshot(): String = logs.joinToString("\n")

    @Synchronized
    fun notifyState() {
        listener?.invoke()
    }
}
