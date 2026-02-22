package cn.idiots.autoclick.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "click_logs")
data class ClickLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val ruleId: Int,
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis()
)
