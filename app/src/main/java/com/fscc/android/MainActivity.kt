package com.fscc.android

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var rgMode: RadioGroup
    private lateinit var rbStrict: RadioButton
    private lateinit var rb90dns: RadioButton
    private lateinit var rbWhitelist: RadioButton
    private lateinit var layoutWhitelist: LinearLayout
    private lateinit var editWhitelist: EditText
    private lateinit var btnSaveWhitelist: Button
    private lateinit var btnToggle: Button
    private lateinit var btnDomains: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClear: Button
    private lateinit var scrollLogs: ScrollView
    private lateinit var txtLogs: TextView

    private val ifaceList = mutableListOf<NetIface>()
    private var lastRunning: Boolean? = null
    private var updatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtStats = findViewById(R.id.txtStats)
        spinnerIfaces = findViewById(R.id.spinnerIfaces)
        switchAutoHotspot = findViewById(R.id.switchAutoHotspot)
        rgMode = findViewById(R.id.rgMode)
        rbStrict = findViewById(R.id.rbStrict)
        rb90dns = findViewById(R.id.rb90dns)
        rbWhitelist = findViewById(R.id.rbWhitelist)
        layoutWhitelist = findViewById(R.id.layoutWhitelist)
        editWhitelist = findViewById(R.id.editWhitelist)
        btnSaveWhitelist = findViewById(R.id.btnSaveWhitelist)
        btnToggle = findViewById(R.id.btnToggle)
        btnDomains = findViewById(R.id.btnDomains)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnClear = findViewById(R.id.btnClear)
        scrollLogs = findViewById(R.id.scrollLogs)
        txtLogs = findViewById(R.id.txtLogs)

        val prefs = getSharedPreferences("fscc_prefs", Context.MODE_PRIVATE)
        switchAutoHotspot.isChecked = prefs.getBoolean("auto_hotspot", true)

        val savedWl = prefs.getString("whitelist_raw", FsccBus.DEFAULT_WHITELIST) ?: FsccBus.DEFAULT_WHITELIST
        editWhitelist.setText(savedWl)
        FsccBus.whitelistDomains = FsccBus.parseDomainList(savedWl)
        val savedExtra = prefs.getString("extra_blocked_raw", "") ?: ""
        FsccBus.extraBlockedDomains = FsccBus.parseDomainList(savedExtra)

        val savedMode = FsccMode.fromPref(
            prefs.getString("app_mode", null),
            prefs.getBoolean("strict_offline", true)
        )
        updatingUi = true
        when (savedMode) {
            FsccMode.STRICT -> rbStrict.isChecked = true
            FsccMode.DNS90 -> rb90dns.isChecked = true
            FsccMode.WHITELIST -> rbWhitelist.isChecked = true
        }
        updatingUi = false

        switchAutoHotspot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_hotspot", checked).apply()
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (updatingUi) return@setOnCheckedChangeListener
            val selectedMode = modeFromChecked(checkedId)
            prefs.edit()
                .putString("app_mode", selectedMode.prefValue)
                .putBoolean("strict_offline", selectedMode == FsccMode.STRICT)
                .apply()
            if (FsccBus.isRunning && FsccBus.mode != selectedMode) {
                val i = Intent(this, FsccService::class.java).apply {
                    action = FsccService.ACTION_SET_MODE
                    putExtra(FsccService.EXTRA_MODE, selectedMode.prefValue)
                }
                startService(i)
            } else {
                updateUi()
            }
        }

        btnSaveWhitelist.setOnClickListener {
            val raw = editWhitelist.text.toString()
            val parsed = FsccBus.parseDomainList(raw)
            prefs.edit().putString("whitelist_raw", raw).apply()
            FsccBus.whitelistDomains = parsed
            FsccBus.log("Whitelist mise à jour (${parsed.size} domaines)")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        btnRefresh.setOnClickListener { refreshInterfaces() }
        btnClear.setOnClickListener { FsccBus.clear() }
        btnDomains.setOnClickListener { showDomainsDialog() }

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
                val wlRaw = editWhitelist.text.toString()
                prefs.edit().putString("whitelist_raw", wlRaw).apply()
                FsccBus.whitelistDomains = FsccBus.parseDomainList(wlRaw)

                val selectedMode = modeFromChecked(rgMode.checkedRadioButtonId)
                val i = Intent(this, FsccService::class.java).apply {
                    action = FsccService.ACTION_START
                    putExtra(FsccService.EXTRA_IFACE, selected?.name ?: "")
                    putExtra(FsccService.EXTRA_IP, selected?.ip ?: "")
                    putExtra(FsccService.EXTRA_MODE, selectedMode.prefValue)
                    putExtra(FsccService.EXTRA_STRICT_OFFLINE, selectedMode == FsccMode.STRICT)
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

    private fun modeFromChecked(checkedId: Int): FsccMode {
        return when (checkedId) {
            R.id.rb90dns -> FsccMode.DNS90
            R.id.rbWhitelist -> FsccMode.WHITELIST
            else -> FsccMode.STRICT
        }
    }

    private fun showDomainsDialog() {
        val entries = FsccBus.seenDomainsSnapshot().reversed()
        val extraCount = FsccBus.extraBlockedDomains.size
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Radar des domaines ($extraCount bloqués perso)")
                .setMessage("Aucun domaine capturé pour le moment.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = entries.map { (domain, status) -> "[$status] $domain" }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle("Domaines détectés (${entries.size}) • Perso: $extraCount")
            .setItems(labels) { _, idx ->
                val domain = entries[idx].first
                showDomainActionDialog(domain)
            }
            .setPositiveButton("Fermer", null)
        if (extraCount > 0) {
            builder.setNeutralButton("Reset bloqués perso") { _, _ ->
                getSharedPreferences("fscc_prefs", Context.MODE_PRIVATE)
                    .edit().putString("extra_blocked_raw", "").apply()
                FsccBus.extraBlockedDomains = emptySet()
                FsccBus.log("Liste noire perso réinitialisée")
            }
        }
        builder.show()
    }

    private fun showDomainActionDialog(domain: String) {
        val prefs = getSharedPreferences("fscc_prefs", Context.MODE_PRIVATE)
        val actions = arrayOf("Bloquer (Blacklist perso)", "Autoriser (Whitelist)")
        AlertDialog.Builder(this)
            .setTitle(domain)
            .setItems(actions) { _, which ->
                if (which == 0) {
                    val cur = (prefs.getString("extra_blocked_raw", "") ?: "")
                        .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableSet()
                    cur.add(domain)
                    val joined = cur.joinToString(", ")
                    prefs.edit().putString("extra_blocked_raw", joined).apply()
                    FsccBus.extraBlockedDomains = FsccBus.parseDomainList(joined)
                    FsccBus.log("Ajouté à la blacklist perso : $domain")
                } else {
                    val cur = FsccBus.parseDomainList(editWhitelist.text.toString()).toMutableSet()
                    cur.add(domain)
                    val joined = cur.joinToString(", ")
                    editWhitelist.setText(joined)
                    prefs.edit().putString("whitelist_raw", joined).apply()
                    FsccBus.whitelistDomains = cur
                    FsccBus.log("Ajouté à la Whitelist : $domain")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            updatingUi = true
            when (FsccBus.mode) {
                FsccMode.STRICT -> rbStrict.isChecked = true
                FsccMode.DNS90 -> rb90dns.isChecked = true
                FsccMode.WHITELIST -> rbWhitelist.isChecked = true
            }
            updatingUi = false
            layoutWhitelist.visibility = if (FsccBus.mode == FsccMode.WHITELIST) View.VISIBLE else View.GONE
            txtStatus.text = "ACTIF [${FsccBus.modeLabel}] sur ${FsccBus.activeIface} (${FsccBus.activeIp})"
            txtStatus.setTextColor(Color.parseColor("#4ADE80"))
            btnToggle.text = "Arrêter"
            spinnerIfaces.isEnabled = false
            switchAutoHotspot.isEnabled = false
        } else {
            val mode = modeFromChecked(rgMode.checkedRadioButtonId)
            layoutWhitelist.visibility = if (mode == FsccMode.WHITELIST) View.VISIBLE else View.GONE
            txtStatus.text = "ARRÊTÉ (Mode prêt : ${mode.label})"
            txtStatus.setTextColor(Color.parseColor("#FF6B6B"))
            btnToggle.text = "Démarrer ${mode.label}"
            spinnerIfaces.isEnabled = true
            switchAutoHotspot.isEnabled = true
        }

        val client = if (FsccBus.lastClientIp.isNotEmpty()) FsccBus.lastClientIp else "—"
        txtStats.text = "Autorisés : ${FsccBus.allowedCount}  |  Bloqués : ${FsccBus.blockedCount}  |  HTTP : ${FsccBus.httpCount}  |  Switch : $client"
        btnDomains.text = "Domaines (${FsccBus.seenDomainsCount()})"

        val text = FsccBus.snapshot()
        if (text.isNotEmpty()) {
            txtLogs.text = text
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        } else {
            txtLogs.text = "Prêt. Choisis un mode (FSCC Strict / 90DNS / Whitelist) puis appuie sur Démarrer.\n"
        }
    }
}
