package cn.idiots.autoclick.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ClickRuleRepository(private val context: Context, private val dao: ClickRuleDao) {

    private val prefs: SharedPreferences = context.getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE)

    val allRules: Flow<List<ClickRule>> = dao.getAllRules()

    // Global state to track if we are currently recording a new rule
    private val _isRecording = MutableStateFlow(prefs.getBoolean("is_recording", false))
    val isRecording: StateFlow<Boolean> = _isRecording

    // Master Switch: Global enable for the auto-clicking logic
    private val _isGlobalEnabled = MutableStateFlow(prefs.getBoolean("is_global_enabled", true))
    val isGlobalEnabled: StateFlow<Boolean> = _isGlobalEnabled

    // Whitelist state for apps enabled for auto-clicking
    private val _enabledPackages = MutableStateFlow(prefs.getStringSet("enabled_packages", emptySet()) ?: emptySet())
    val enabledPackages: StateFlow<Set<String>> = _enabledPackages

    fun togglePackageEnabled(packageName: String, isEnabled: Boolean) {
        val currentSet = _enabledPackages.value.toMutableSet()
        if (isEnabled) {
            currentSet.add(packageName)
        } else {
            currentSet.remove(packageName)
        }
        prefs.edit().putStringSet("enabled_packages", currentSet).apply()
        _enabledPackages.value = currentSet
    }

    fun toggleRecording() {
        val newState = !_isRecording.value
        prefs.edit().putBoolean("is_recording", newState).apply()
        _isRecording.value = newState
    }
    
    fun toggleGlobalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_global_enabled", enabled).apply()
        _isGlobalEnabled.value = enabled
    }

    fun stopRecording() {
        prefs.edit().putBoolean("is_recording", false).apply()
        _isRecording.value = false
    }
    
    // Call this to sync up state when the accessibility service / app resumes
    fun syncRecordingState() {
        _isRecording.value = prefs.getBoolean("is_recording", false)
        _isGlobalEnabled.value = prefs.getBoolean("is_global_enabled", true)
    }

    suspend fun getActiveRulesForPackage(packageName: String): List<ClickRule> {
        return dao.getActiveRulesForPackage(packageName)
    }

    suspend fun insertRule(rule: ClickRule) {
        dao.insertRule(rule)
    }

    suspend fun updateRule(rule: ClickRule) {
        dao.updateRule(rule)
    }

    suspend fun deleteRule(rule: ClickRule) {
        dao.deleteRule(rule)
    }

    suspend fun installSubscription(subscriptionName: String, rules: List<ClickRule>) {
        dao.deleteSubscription(subscriptionName) // clear old rules for this sub
        dao.insertRules(rules)
    }

    suspend fun removeSubscription(subscriptionName: String) {
        dao.deleteSubscription(subscriptionName)
    }

    val recentLogs: Flow<List<ClickLog>> = dao.getRecentLogs()
    val totalClicks: Flow<Long> = dao.getTotalClickCount()

    suspend fun logClick(rule: ClickRule) {
        val log = ClickLog(
            ruleId = rule.id,
            packageName = rule.packageName,
            appName = rule.appName
        )
        dao.insertClickLog(log)
    }
}
