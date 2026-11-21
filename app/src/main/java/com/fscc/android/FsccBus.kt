package com.fscc.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FsccBus {
    @Volatile var isRunning: Boolean = false
    @Volatile var activeIface: String = ""
    @Volatile var activeIp: String = ""

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
    fun clear() {
        logs.clear()
        listener?.invoke()
    }

    @Synchronized
    fun snapshot(): String = logs.joinToString("\n")

    @Synchronized
    fun notifyState() {
        listener?.invoke()
    }
}
