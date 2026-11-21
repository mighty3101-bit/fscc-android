package com.fscc.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class FsccTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (FsccBus.isRunning) {
            val i = Intent(this, FsccService::class.java).apply {
                action = FsccService.ACTION_STOP
            }
            startService(i)
        } else {
            val prefs = getSharedPreferences("fscc_prefs", Context.MODE_PRIVATE)
            val strict = prefs.getBoolean("strict_offline", true)
            val i = Intent(this, FsccService::class.java).apply {
                action = FsccService.ACTION_START
                putExtra(FsccService.EXTRA_IFACE, "")
                putExtra(FsccService.EXTRA_IP, "")
                putExtra(FsccService.EXTRA_STRICT_OFFLINE, strict)
                putExtra(FsccService.EXTRA_AUTO_HOTSPOT, true)
            }
            ContextCompat.startForegroundService(this, i)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        if (FsccBus.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "FSCC Switch"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "${FsccBus.activeIface} (${FsccBus.activeIp})"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "FSCC Switch"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Hors-ligne Switch"
            }
        }
        tile.updateTile()
    }
}
