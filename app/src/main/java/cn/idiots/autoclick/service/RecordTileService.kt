package cn.idiots.autoclick.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cn.idiots.autoclick.AutoClickApplication

class RecordTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val repository = (application as AutoClickApplication).repository
        
        // Toggle the recording state
        repository.toggleRecording()
        
        val isRecording = repository.isRecording.value
        updateTileState(isRecording)
        
        android.widget.Toast.makeText(this, if (isRecording) "录制模式: 已开启" else "录制模式: 已关闭", android.widget.Toast.LENGTH_SHORT).show()
        
        // Close the notification panel so user can click the target app
        // For Android 12 (API 31) and above, ACTION_CLOSE_SYSTEM_DIALOGS is restricted. 
        // Best practice is to use AccessibilityService's GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                AutoClickAccessibilityService.instance?.performGlobalAction(15) // AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            } else {
                @Suppress("DEPRECATION")
                val it = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                sendBroadcast(it)
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoClicker", "Failed to close system dialogs", e)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val repository = (application as AutoClickApplication).repository
        updateTileState(repository.isRecording.value)
    }

    private fun updateTileState(isRecording: Boolean) {
        val tile = qsTile
        if (tile != null) {
            if (isRecording) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Recording..."
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Record Rule"
            }
            tile.updateTile()
        }
    }
}
