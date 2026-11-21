package com.fscc.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FsccBus {
    @Volatile var isRunning: Boolean = false
    @Volatile var activeIface: String = ""
    @Volatile var activeIp: String = ""
    @Volatile var modeLabel: String = "FSCC STRICT"
    @Volatile var allowedCount: Int = 0
    @Volatile var blockedCount: Int = 0
    @Volatile var httpCount: Int = 0
    @Volatile var lastClientIp: String = ""

    private val logs = ArrayDeque<String>(250)
    private var listener: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
    fun resetStats() {
        allowedCount = 0
        blockedCount = 0
        httpCount = 0
        lastClientIp = ""
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
        listener?.invoke()
    }

    @Synchronized
    fun snapshot(): String = logs.joinToString("\n")

    @Synchronized
    fun notifyState() {
        listener?.invoke()
    }
}
