package cn.idiots.autoclick.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.idiots.autoclick.data.ClickRule
import cn.idiots.autoclick.data.ClickRuleRepository
import cn.idiots.autoclick.util.GkdSubscriptionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import cn.idiots.autoclick.util.AppManager
import cn.idiots.autoclick.util.AppInfo
import android.app.Application

class RuleViewModel(application: Application, private val repository: ClickRuleRepository) : ViewModel() {

    private val appContext = application.applicationContext

    private val _importingState = MutableStateFlow<String?>(null)
    val importingState: StateFlow<String?> = _importingState.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    val enabledPackages: StateFlow<Set<String>> = repository.enabledPackages
    val isGlobalEnabled: StateFlow<Boolean> = repository.isGlobalEnabled


    val rules: StateFlow<List<ClickRule>> = repository.allRules
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val manualGroupedRules: StateFlow<Map<String, List<ClickRule>>> = repository.allRules
        .map { list -> list.filter { !it.isSubscription }.groupBy { it.packageName } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    // Grouping by SubscriptionName -> PackageName -> Rules
    val subscribedGroupedRules: StateFlow<Map<String, Map<String, List<ClickRule>>>> = repository.allRules
        .map { list -> 
            list.filter { it.isSubscription }
                .groupBy { it.subscriptionName ?: "Unknown Subscription" }
                .mapValues { (_, subRules) -> subRules.groupBy { it.packageName } } 
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val isRecording: StateFlow<Boolean> = repository.isRecording

    val logs: StateFlow<List<cn.idiots.autoclick.data.ClickLog>> = repository.recentLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val totalClicks: StateFlow<Long> = repository.totalClicks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0L
        )

    fun toggleRule(rule: ClickRule) {
        viewModelScope.launch {
            repository.updateRule(rule.copy(isEnabled = !rule.isEnabled))
        }
    }

    fun deleteRule(rule: ClickRule) {
        viewModelScope.launch {
            repository.deleteRule(rule)
        }
    }

    fun updateRule(rule: ClickRule) {
        viewModelScope.launch {
            repository.updateRule(rule)
        }
    }

    fun importGkdRules(rules: List<ClickRule>) {
        viewModelScope.launch {
            rules.forEach { repository.insertRule(it) }
        }
    }

    fun addManualRule(rule: ClickRule) {
        viewModelScope.launch {
            repository.insertRule(rule)
        }
    }

    fun importSubscriptionFromUrl(url: String) {
        viewModelScope.launch {
            _importingState.value = "Fetching from URL..."
            val result = GkdSubscriptionManager.fetchAndParseUrl(url)
            result.onSuccess { rules ->
                val subName = rules.firstOrNull()?.subscriptionName ?: "Imported URL"
                repository.installSubscription(subName, rules)
                _importingState.value = "成功: 导入了 ${rules.size} 条规则。"
            }.onFailure { e ->
                _importingState.value = "错误: ${e.message}"
            }
        }
    }

    fun importSubscriptionFromFile(content: String, defaultName: String = "Imported File") {
        viewModelScope.launch {
            _importingState.value = "Parsing file..."
            val result = GkdSubscriptionManager.parseContent(content)
            result.onSuccess { rules ->
                // Override subscription name if it wasn't provided in the file
                val subName = rules.firstOrNull()?.subscriptionName?.takeIf { it != "Imported Subscription" } ?: defaultName
                val finalRules = rules.map { it.copy(subscriptionName = subName) }
                repository.installSubscription(subName, finalRules)
                _importingState.value = "成功: 导入了 ${finalRules.size} 条规则。"
            }.onFailure { e ->
                _importingState.value = "错误: ${e.message}"
            }
        }
    }

    fun removeSubscription(subscriptionName: String) {
        viewModelScope.launch {
            repository.removeSubscription(subscriptionName)
        }
    }
    
    fun clearImportState() {
        _importingState.value = null
    }

    fun loadInstalledApps(forceRefresh: Boolean = false) {
        if (forceRefresh || _installedApps.value.isEmpty()) {
            if (_isLoadingApps.value) return // Prevent multiple concurrent loads
            
            _isLoadingApps.value = true
            viewModelScope.launch {
                val apps = withContext(Dispatchers.IO) {
                    AppManager.getInstalledUserApps(appContext)
                }
                _installedApps.value = apps
                _isLoadingApps.value = false
            }
        }
    }

    fun togglePackageEnabled(packageName: String, isEnabled: Boolean) {
        repository.togglePackageEnabled(packageName, isEnabled)
    }

    fun toggleGlobalEnabled(enabled: Boolean) {
        repository.toggleGlobalEnabled(enabled)
    }
}

class RuleViewModelFactory(private val application: Application, private val repository: ClickRuleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RuleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RuleViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
