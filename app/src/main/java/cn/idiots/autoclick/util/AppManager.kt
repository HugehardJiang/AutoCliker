package cn.idiots.autoclick.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

object AppManager {

    /**
     * Gets a list of all installed non-system applications on the device.
     */
    fun getInstalledUserApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val userApps = mutableListOf<AppInfo>()

        for (appInfo in packages) {
            // We want apps that have a launcher intent (can be opened by the user)
            // This natively includes standard user-facing system apps like Google Play Store
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    null
                }
                userApps.add(AppInfo(appInfo.packageName, appName, icon))
            }
        }
        
        // Sort alphabetically
        return userApps.sortedBy { it.appName.lowercase() }
    }
}
