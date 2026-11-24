package com.snapedge.app.service

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * On-demand Quick Settings Tile so users can trigger Circle & Search anytime
 * without leaving background services running.
 */
class SnapEdgeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Circle & Search"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant Display over other apps in SnapEdge first", Toast.LENGTH_SHORT).show()
            return
        }
        EdgeOverlayService.start(this, EdgeOverlayService.ACTION_CIRCLE_OCR)
    }
}
