package cn.idiots.autoclick

import android.app.Application
import cn.idiots.autoclick.data.AppDatabase
import cn.idiots.autoclick.data.ClickRuleRepository

class AutoClickApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { ClickRuleRepository(this, database.clickRuleDao()) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "服务状态通知"
            val descriptionText = "用于显示无障碍服务状态及操作提示"
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = android.app.NotificationChannel("autoclick_status", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
