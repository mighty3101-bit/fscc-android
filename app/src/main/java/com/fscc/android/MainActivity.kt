package com.fscc.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    data class NetIface(val name: String, val ip: String) {
        override fun toString(): String = "$name  ($ip)"
    }

    private lateinit var txtStatus: TextView
    private lateinit var txtStats: TextView
    private lateinit var spinnerIfaces: Spinner
    private lateinit var switchAutoHotspot: SwitchCompat
    private lateinit var switchOffline: SwitchCompat
    private lateinit var btnToggle: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClear: Button
    private lateinit var scrollLogs: ScrollView
    private lateinit var txtLogs: TextView

    private val ifaceList = mutableListOf<NetIface>()
    private var lastRunning: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtStats = findViewById(R.id.txtStats)
        spinnerIfaces = findViewById(R.id.spinnerIfaces)
        switchAutoHotspot = findViewById(R.id.switchAutoHotspot)
        switchOffline = findViewById(R.id.switchOffline)
        btnToggle = findViewById(R.id.btnToggle)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnClear = findViewById(R.id.btnClear)
        scrollLogs = findViewById(R.id.scrollLogs)
        txtLogs = findViewById(R.id.txtLogs)

        val prefs = getSharedPreferences("fscc_prefs", Context.MODE_PRIVATE)
        switchAutoHotspot.isChecked = prefs.getBoolean("auto_hotspot", true)
        switchOffline.isChecked = prefs.getBoolean("strict_offline", true)

        switchAutoHotspot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_hotspot", checked).apply()
        }
        switchOffline.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("strict_offline", checked).apply()
            updateUi()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        btnRefresh.setOnClickListener { refreshInterfaces() }
        btnClear.setOnClickListener { FsccBus.clear() }
        btnToggle.setOnClickListener {
            if (FsccBus.isRunning) {
                val i = Intent(this, FsccService::class.java).apply { action = FsccService.ACTION_STOP }
                startService(i)
            } else {
                val autoHotspot = switchAutoHotspot.isChecked
                val selected = spinnerIfaces.selectedItem as? NetIface
                if (!autoHotspot && selected == null) {
                    FsccBus.log("ERREUR : Aucune interface IPv4 détectée (allume le hotspot !)")
                    return@setOnClickListener
                }
                val i = Intent(this, FsccService::class.java).apply {
                    action = FsccService.ACTION_START
                    putExtra(FsccService.EXTRA_IFACE, selected?.name ?: "")
                    putExtra(FsccService.EXTRA_IP, selected?.ip ?: "")
                    putExtra(FsccService.EXTRA_STRICT_OFFLINE, switchOffline.isChecked)
                    putExtra(FsccService.EXTRA_AUTO_HOTSPOT, autoHotspot)
                }
                ContextCompat.startForegroundService(this, i)
            }
        }

        refreshInterfaces()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        FsccBus.setListener { runOnUiThread { updateUi() } }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        FsccBus.setListener(null)
    }

    private fun refreshInterfaces() {
        ifaceList.clear()
        val hotspotPriority = listOf("wlan2", "swlan0", "ap0", "softap0", "wlan1", "rndis0", "ncm0")
        try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ip -> ifaceList.add(NetIface(ni.name, ip)) }
                    }
                }
            }
        } catch (e: Exception) {
            FsccBus.log("Erreur lecture interfaces: ${e.message}")
        }

        ifaceList.sortBy { item ->
            val idx = hotspotPriority.indexOf(item.name)
            if (idx >= 0) idx else 100
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ifaceList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerIfaces.adapter = adapter
    }

    private fun updateUi() {
        if (lastRunning != FsccBus.isRunning) {
            lastRunning = FsccBus.isRunning
            refreshInterfaces()
            if (!FsccBus.isRunning) {
                txtLogs.postDelayed({ refreshInterfaces() }, 1500)
            }
        }
        if (FsccBus.isRunning) {
            txtStatus.text = "ACTIF [${FsccBus.modeLabel}] sur ${FsccBus.activeIface} (${FsccBus.activeIp})"
            txtStatus.setTextColor(Color.parseColor("#4ADE80"))
            btnToggle.text = "Arrêter"
            spinnerIfaces.isEnabled = false
            switchAutoHotspot.isEnabled = false
            switchOffline.isEnabled = false
        } else {
            val mode = if (switchOffline.isChecked) "FSCC STRICT" else "90DNS INTERNET"
            txtStatus.text = "ARRÊTÉ (Mode prêt : $mode)"
            txtStatus.setTextColor(Color.parseColor("#FF6B6B"))
            btnToggle.text = if (switchOffline.isChecked) "Démarrer FSCC Strict" else "Démarrer 90DNS"
            spinnerIfaces.isEnabled = true
            switchAutoHotspot.isEnabled = true
            switchOffline.isEnabled = true
        }
        val client = if (FsccBus.lastClientIp.isNotEmpty()) FsccBus.lastClientIp else "—"
        txtStats.text = "Autorisés : ${FsccBus.allowedCount}  |  Bloqués : ${FsccBus.blockedCount}  |  HTTP : ${FsccBus.httpCount}  |  Switch : $client"
        val text = FsccBus.snapshot()
        if (text.isNotEmpty()) {
            txtLogs.text = text
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        } else {
            txtLogs.text = "Prêt. Choisis FSCC Strict ou 90DNS puis appuie sur Démarrer.\n"
        }
    }
}
