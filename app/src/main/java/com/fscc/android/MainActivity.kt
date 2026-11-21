package com.fscc.android

import android.Manifest
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
        spinnerIfaces = findViewById(R.id.spinnerIfaces)
        switchAutoHotspot = findViewById(R.id.switchAutoHotspot)
        switchOffline = findViewById(R.id.switchOffline)
        btnToggle = findViewById(R.id.btnToggle)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnClear = findViewById(R.id.btnClear)
        scrollLogs = findViewById(R.id.scrollLogs)
        txtLogs = findViewById(R.id.txtLogs)

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
            txtStatus.text = "Statut : ACTIF sur ${FsccBus.activeIface} (${FsccBus.activeIp})"
            txtStatus.setTextColor(Color.parseColor("#4ADE80"))
            btnToggle.text = "Arrêter FSCC"
            spinnerIfaces.isEnabled = false
            switchAutoHotspot.isEnabled = false
            switchOffline.isEnabled = false
        } else {
            txtStatus.text = "Statut : ARRÊTÉ"
            txtStatus.setTextColor(Color.parseColor("#FF6B6B"))
            btnToggle.text = "Démarrer FSCC"
            spinnerIfaces.isEnabled = true
            switchAutoHotspot.isEnabled = true
            switchOffline.isEnabled = true
        }
        val text = FsccBus.snapshot()
        if (text.isNotEmpty()) {
            txtLogs.text = text
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        } else {
            txtLogs.text = "Prêt. Appuie sur Démarrer FSCC (le hotspot démarrera tout seul).\n"
        }
    }
}
