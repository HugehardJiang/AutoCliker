package cn.idiots.autoclick.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class DumpTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "无障碍服务未连接", Toast.LENGTH_SHORT).show()
            return
        }

        // Collapse panels so we don't dump the notification shade itself
        service.performGlobalAction(15) // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE

        // Wait a bit for shade to close with a countdown
        Toast.makeText(this, "2秒后开始捕获...", Toast.LENGTH_SHORT).show()
        
        android.os.Handler(mainLooper).postDelayed({
            service.dumpHierarchy()
        }, 2000)
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }
}
