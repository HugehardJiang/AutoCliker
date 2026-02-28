package cn.idiots.autoclick

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.app.ActivityManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import cn.idiots.autoclick.data.ClickRule
import cn.idiots.autoclick.ui.theme.AutoClickerTheme
import cn.idiots.autoclick.ui.viewmodel.RuleViewModel
import cn.idiots.autoclick.ui.viewmodel.RuleViewModelFactory
import cn.idiots.autoclick.util.GkdParser
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val viewModel: RuleViewModel by viewModels {
        RuleViewModelFactory(application, (application as AutoClickApplication).repository)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request Notification Permission for Android 13+
        requestNotificationPermission()

        // Enable Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Apply protection setting early
        val sharedPref = getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("hide_from_recents", false)) {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .appTasks.firstOrNull()?.setExcludeFromRecents(true)
        }

        setContent {
            AutoClickerTheme {
                val dashboardStats by viewModel.totalClicks.collectAsState()
                val recentLogs by viewModel.logs.collectAsState()
                val manualRules by viewModel.manualGroupedRules.collectAsState()
                val subscribedRules by viewModel.subscribedGroupedRules.collectAsState()
                val isRecording by viewModel.isRecording.collectAsState()
                
                var currentTab by remember { mutableStateOf(Tab.Dashboard) }
                val hazeState = remember { HazeState() }
                
                // Inter-tab Navigation State
                var targetRuleId by remember { mutableStateOf<Int?>(null) }
                
                val navToRule: (Int) -> Unit = { id ->
                    targetRuleId = id
                    currentTab = Tab.Rules
                }

                Scaffold(
                    topBar = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .hazeChild(state = hazeState)
                                .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)), RectangleShape)
                        ) {
                            CenterAlignedTopAppBar(
                                title = { 
                                    val title = when(currentTab) {
                                        Tab.Dashboard -> "运行状态"
                                        Tab.Rules -> "规则管理"
                                        Tab.Whitelist -> "白名单"
                                        Tab.Settings -> "软件设置"
                                    }
                                    Text(title, fontWeight = FontWeight.Black) 
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = Color.Transparent
                                )
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0) // Explicitly zero out to control manually
                ) { paddingValues ->
                    // Remove top padding from current Box to let content flow UNDER topBar
                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "TabTransition",
                            modifier = Modifier.fillMaxSize().haze(state = hazeState)
                        ) { targetTab ->
                            // Add top padding INSIDE screens to push content below the title, 
                            // but background/haze remains under topBar
                            val topBarHeight = paddingValues.calculateTopPadding()
                            val bottomPadding = paddingValues.calculateBottomPadding() + 120.dp
                            Box(modifier = Modifier.fillMaxSize().padding(top = topBarHeight)) {
                                when (targetTab) {
                                    Tab.Dashboard -> DashboardScreen(
                                        totalClicks = dashboardStats,
                                        recentLogs = recentLogs,
                                        viewModel = viewModel,
                                        onNavigateToRule = navToRule,
                                        bottomPadding = bottomPadding
                                    )
                                    Tab.Rules -> RulesScreen(
                                        manualGroupedRules = manualRules,
                                        subscribedGroupedRules = subscribedRules,
                                        targetRuleId = targetRuleId,
                                        onRuleTargetConsumed = { targetRuleId = null },
                                        viewModel = viewModel,
                                        bottomPadding = bottomPadding
                                    )
                                    Tab.Whitelist -> AppWhitelistScreen(
                                        viewModel = viewModel,
                                        bottomPadding = bottomPadding
                                    )
                                    Tab.Settings -> SettingsScreen(
                                        bottomPadding = bottomPadding
                                    )
                                }
                            }
                        }

                        // The Floating Glass Navigation Bar
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = paddingValues.calculateBottomPadding())
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .clip(RoundedCornerShape(32.dp))
                                    .hazeChild(state = hazeState, shape = RoundedCornerShape(32.dp))
                                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(32.dp)),
                                windowInsets = WindowInsets(0, 0, 0, 0) // Handle insets manually to keep it floating
                            ) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    label = { Text("主页") },
                                    selected = currentTab == Tab.Dashboard,
                                    onClick = { currentTab = Tab.Dashboard },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    )
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    label = { Text("规则") },
                                    selected = currentTab == Tab.Rules,
                                    onClick = { currentTab = Tab.Rules },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    )
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                                    label = { Text("白名单") },
                                    selected = currentTab == Tab.Whitelist,
                                    onClick = { currentTab = Tab.Whitelist },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    )
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("设置") },
                                    selected = currentTab == Tab.Settings,
                                    onClick = { currentTab = Tab.Settings },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class Tab { Dashboard, Rules, Whitelist, Settings }

@Composable
fun DashboardScreen(
    totalClicks: Long, 
    recentLogs: List<cn.idiots.autoclick.data.ClickLog>, 
    viewModel: RuleViewModel,
    onNavigateToRule: (Int) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val clusteredLogs = remember(recentLogs) { clusterLogs(recentLogs) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + bottomPadding)
    ) {
        item { ServiceStatusCard(viewModel) }
        item { OptimizationCard() }
        item { AutoRestartCard() }
        
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)), RoundedCornerShape(24.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .align(Alignment.CenterStart)
                    ) {
                        Text(
                            "累计跳过", 
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = totalClicks.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier
                            .size(120.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 30.dp, y = 30.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
                    )
                }
            }
        }

        item {
            Text(
                "近期活动",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        if (clusteredLogs.isEmpty()) {
            item {
                Text(
                    "暂无跳过记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                )
            }
        } else {
            items(clusteredLogs) { cluster ->
                LogClusterItem(cluster, viewModel, onNavigateToRule)
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Made with ❤️ by Hugehard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                val context = androidx.compose.ui.platform.LocalContext.current
                Text(
                    "https://www.idiots.cn/about",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.idiots.cn/about"))
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

data class LogCluster(
    val appName: String,
    val packageName: String,
    val count: Int,
    val lastTimestamp: Long,
    val logs: List<cn.idiots.autoclick.data.ClickLog>
)

fun clusterLogs(logs: List<cn.idiots.autoclick.data.ClickLog>): List<LogCluster> {
    if (logs.isEmpty()) return emptyList()
    
    val result = mutableListOf<LogCluster>()
    var currentCluster: LogCluster? = null
    var currentLogs = mutableListOf<cn.idiots.autoclick.data.ClickLog>()
    
    for (log in logs) {
        if (currentCluster == null) {
            currentLogs.add(log)
            currentCluster = LogCluster(log.appName, log.packageName, 1, log.timestamp, currentLogs.toList())
        } else if (currentCluster.packageName == log.packageName) {
            currentLogs.add(log)
            currentCluster = currentCluster.copy(count = currentCluster.count + 1, lastTimestamp = maxOf(currentCluster.lastTimestamp, log.timestamp), logs = currentLogs.toList())
        } else {
            result.add(currentCluster)
            currentLogs = mutableListOf(log)
            currentCluster = LogCluster(log.appName, log.packageName, 1, log.timestamp, currentLogs.toList())
        }
    }
    if (currentCluster != null) result.add(currentCluster)
    return result
}

@Composable
fun LogClusterItem(cluster: LogCluster, viewModel: RuleViewModel, onNavigateToRule: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = sdf.format(Date(cluster.lastTimestamp))
    val rules by viewModel.rules.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    
    // Dynamically look up the readable app name. If not found, fallback to the DB stored name.
    val displayAppName = installedApps.find { it.packageName == cluster.packageName }?.appName ?: cluster.appName
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        displayAppName.take(1).uppercase(), 
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayAppName, 
                            fontWeight = FontWeight.Bold, 
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (cluster.count > 1) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    " x${cluster.count} ", 
                                    style = MaterialTheme.typography.labelSmall, 
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        cluster.packageName, 
                        style = MaterialTheme.typography.bodySmall, 
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 4.dp).size(20.dp)
                    )
                }
            }
            
            if (expanded) {
                val triggeredRuleIds = cluster.logs.map { it.ruleId }.distinct()
                val matchedRules = triggeredRuleIds.mapNotNull { id -> rules.find { it.id == id } }
                
                if (matchedRules.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("触发的规则:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                        matchedRules.forEach { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToRule(rule.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.ruleDescription ?: "常规规则", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    val target = if (!rule.selector.isNullOrEmpty()) rule.selector
                                                 else if (!rule.targetText.isNullOrEmpty()) rule.targetText 
                                                 else if (!rule.targetViewId.isNullOrEmpty()) rule.targetViewId.substringAfterLast("/") 
                                                 else "坐标点击"
                                    Text(target ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                ScaledSwitch(
                                    checked = rule.isEnabled,
                                    onCheckedChange = { viewModel.toggleRule(rule) },
                                    scale = 0.7f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RulesScreen(
    manualGroupedRules: Map<String, List<ClickRule>>,
    subscribedGroupedRules: Map<String, Map<String, List<ClickRule>>>,
    targetRuleId: Int?,
    onRuleTargetConsumed: () -> Unit,
    viewModel: RuleViewModel,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    var showImportDialog by remember { mutableStateOf(false) }
    val importingState by viewModel.importingState.collectAsState()

    if (showImportDialog) {
        ImportSubscriptionDialog(
            onDismiss = { showImportDialog = false },
            onImportUrl = { url ->
                viewModel.importSubscriptionFromUrl(url)
                showImportDialog = false
            },
            onImportFile = { content ->
                viewModel.importSubscriptionFromFile(content)
                showImportDialog = false
            }
        )
    }

    if (importingState != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearImportState() },
            title = { Text("导入状态") },
            text = { Text(importingState!!) },
            confirmButton = { TextButton(onClick = { viewModel.clearImportState() }) { Text("OK") } }
        )
    }

    val apps by viewModel.installedApps.collectAsState()
    val enabledPackages by viewModel.enabledPackages.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
    }

    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateRuleDialog(
            apps = apps,
            onDismiss = { showCreateDialog = false },
            onSave = { rule ->
                viewModel.addManualRule(rule)
                viewModel.togglePackageEnabled(rule.packageName, true)
                showCreateDialog = false
            }
        )
    }

    var isHeaderVisible by remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -5) isHeaderVisible = false
                if (available.y > 5) isHeaderVisible = true
                return Offset.Zero
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        AnimatedVisibility(
            visible = isHeaderVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("规则管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { showImportDialog = true }) {
                    Text("导入规则")
                }
            }
        }


        if (manualGroupedRules.isEmpty() && subscribedGroupedRules.isEmpty()) {
            EmptyStateView()
        } else {
            val listState = rememberLazyListState()
            
            LaunchedEffect(targetRuleId) {
                if (targetRuleId != null) {
                    var targetIndex = -1
                    var currentIndex = 0
                    
                    if (manualGroupedRules.isNotEmpty()) {
                        currentIndex++ // "手动添加的规则" header
                        for ((packageName, rules) in manualGroupedRules) {
                            if (packageName == "*" || enabledPackages.contains(packageName)) {
                                if (rules.any { it.id == targetRuleId }) {
                                    targetIndex = currentIndex
                                    break
                                }
                                currentIndex++
                            }
                        }
                    }
                    
                    if (targetIndex == -1 && subscribedGroupedRules.isNotEmpty()) {
                        currentIndex++ // "订阅规则" header
                        for ((subName, appsMap) in subscribedGroupedRules) {
                            val filteredAppsMap = appsMap.filterKeys { it == "*" || enabledPackages.contains(it) }
                            if (filteredAppsMap.isNotEmpty()) {
                                if (filteredAppsMap.values.flatten().any { it.id == targetRuleId }) {
                                    targetIndex = currentIndex
                                    break
                                }
                                currentIndex++
                            }
                        }
                    }
                    
                    if (targetIndex != -1) {
                        try {
                            // Wait a moment for composition to settle before scrolling
                            kotlinx.coroutines.delay(100)
                            listState.animateScrollToItem(targetIndex)
                        } catch (e: Exception) {
                            // Ignore scroll cancellation
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 48.dp + bottomPadding)
            ) {
                if (manualGroupedRules.isNotEmpty()) {
                    item {
                        Text("手动添加的规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    manualGroupedRules.forEach { (packageName, rules) ->
                        if (packageName == "*" || enabledPackages.contains(packageName)) {
                            item(key = "manual_$packageName") {
                                AppRuleGroup(
                                    packageName = packageName,
                                    appName = rules.firstOrNull()?.appName ?: "未知应用",
                                    rules = rules,
                                    targetRuleId = targetRuleId,
                                    onRuleTargetConsumed = onRuleTargetConsumed,
                                    onToggleRule = { viewModel.toggleRule(it) },
                                    onDeleteRule = { viewModel.deleteRule(it) },
                                    onEditRule = { viewModel.updateRule(it) }
                                )
                            }
                        }
                    }
                }

                if (subscribedGroupedRules.isNotEmpty()) {
                    item {
                        Text("订阅规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    subscribedGroupedRules.forEach { (subName, appsMap) ->
                        val filteredAppsMap = appsMap.filterKeys { it == "*" || enabledPackages.contains(it) }
                        if (filteredAppsMap.isNotEmpty()) {
                            item(key = "sub_$subName") {
                                SubscriptionGroup(
                                    subName = subName,
                                    appsMap = filteredAppsMap,
                                    targetRuleId = targetRuleId,
                                    onRuleTargetConsumed = onRuleTargetConsumed,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
        } // Close Column
        
        androidx.compose.material3.FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp + bottomPadding, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "新建规则", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun SubscriptionGroup(
    subName: String,
    appsMap: Map<String, List<ClickRule>>,
    targetRuleId: Int?,
    onRuleTargetConsumed: () -> Unit,
    viewModel: RuleViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    
    LaunchedEffect(targetRuleId) {
        if (targetRuleId != null && appsMap.values.flatten().any { it.id == targetRuleId }) {
            expanded = true
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.3f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(subName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Text("${appsMap.size} 款应用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val subUrl = appsMap.values.flatten().firstOrNull()?.subscriptionUrl
                    if (!subUrl.isNullOrEmpty()) {
                        IconButton(onClick = { viewModel.importSubscriptionFromUrl(subUrl) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "更新", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = { viewModel.removeSubscription(subName) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
            }
            
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(bottom = 16.dp, start = 12.dp, end = 12.dp)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    appsMap.forEach { (packageName, rules) ->
                        AppRuleGroup(
                            packageName = packageName,
                            appName = rules.firstOrNull()?.appName ?: "未知应用",
                            rules = rules,
                            targetRuleId = targetRuleId,
                            onRuleTargetConsumed = onRuleTargetConsumed,
                            onToggleRule = { viewModel.toggleRule(it) },
                            onDeleteRule = { viewModel.deleteRule(it) },
                            onEditRule = { viewModel.updateRule(it) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AppRuleGroup(
    packageName: String,
    appName: String,
    rules: List<ClickRule>,
    targetRuleId: Int?,
    onRuleTargetConsumed: () -> Unit,
    onToggleRule: (ClickRule) -> Unit,
    onDeleteRule: (ClickRule) -> Unit,
    onEditRule: (ClickRule) -> Unit,
    compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    
    LaunchedEffect(targetRuleId) {
        if (targetRuleId != null && rules.any { it.id == targetRuleId }) {
            expanded = true
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) 12.dp else 20.dp)),
        colors = CardDefaults.cardColors(containerColor = if (compact) Color.Transparent else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 0.dp else 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(if (compact) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(appName.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(appName, fontWeight = FontWeight.Bold, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
                            if (rules.any { it.ruleKey != null || !it.preKeys.isNullOrEmpty() }) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = " 连招 ",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                        Text("${rules.size} 条规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    // Sorting rules by ruleKey to show sequence correctly
                    val sortedRules = rules.sortedWith(compareBy({ it.groupKey }, { it.ruleKey ?: Int.MAX_VALUE }))
                    sortedRules.forEach { rule ->
                        SubRuleItem(
                            rule = rule,
                            targetRuleId = targetRuleId,
                            onRuleTargetConsumed = onRuleTargetConsumed,
                            onToggle = { onToggleRule(rule) },
                            onDelete = { onDeleteRule(rule) },
                            onEdit = { onEditRule(it) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubRuleItem(
    rule: ClickRule,
    targetRuleId: Int?,
    onRuleTargetConsumed: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (ClickRule) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val isTarget = targetRuleId == rule.id
    var highlightAlpha by remember { mutableStateOf(0f) }
    
    LaunchedEffect(isTarget) {
        if (isTarget) {
            // Wait for parent expansion animations to settle before requesting focus
            kotlinx.coroutines.delay(300) 
            try {
                bringIntoViewRequester.bringIntoView()
            } catch (e: Exception) {
                // Ignore cancellation exceptions from fast scrolling
            }
            highlightAlpha = 0.3f
            kotlinx.coroutines.delay(1000)
            highlightAlpha = 0f
            onRuleTargetConsumed()
        }
    }
    
    val animatedColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha),
        animationSpec = tween(durationMillis = 800),
        label = "HighlightAnimation"
    )

    if (showEditDialog) {
        EditRuleDialog(
            rule = rule,
            onDismiss = { showEditDialog = false },
            onConfirm = { updatedRule ->
                onEdit(updatedRule)
                showEditDialog = false
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .background(animatedColor)
            .clickable { showEditDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val target = if (!rule.selector.isNullOrEmpty()) rule.selector
                         else if (!rule.targetText.isNullOrEmpty()) rule.targetText 
                         else if (!rule.targetViewId.isNullOrEmpty()) rule.targetViewId.substringAfterLast("/") 
                         else "坐标点击"
            
            Text(
                text = rule.ruleDescription ?: "常规规则",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = target ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            
            // Phase 12: Display exclusion condition if present
            if (!rule.excludeCondition.isNullOrEmpty()) {
                Text(
                    text = "[排除] ${rule.excludeCondition}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Phase 20 Enhancement: Display sequence info in SubRuleItem
            if (rule.ruleKey != null || !rule.preKeys.isNullOrEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rule.groupKey != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = " 组:${rule.groupKey} ",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (rule.ruleKey != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = " ${rule.ruleKey} ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    if (!rule.preKeys.isNullOrEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = " 依赖步骤: ${rule.preKeys}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (rule.ruleKey != null) {
                        Text(
                            text = " 起始步骤 ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScaledSwitch(
                checked = rule.isEnabled,
                onCheckedChange = { onToggle() },
                scale = 0.8f // Scale down for multi-rule list
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun ScaledSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.scale(scale)
    )
}

@Composable
fun OptimizationCard() {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE) }
    var hideFromRecents by remember { 
        mutableStateOf(sharedPref.getBoolean("hide_from_recents", false)) 
    }

    // Effect to apply the setting
    LaunchedEffect(hideFromRecents) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.firstOrNull()?.setExcludeFromRecents(hideFromRecents)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "后台保活",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "在最近任务中隐藏本应用，防止被系统查杀",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = hideFromRecents,
                onCheckedChange = { 
                    hideFromRecents = it
                    sharedPref.edit().putBoolean("hide_from_recents", it).apply()
                }
            )
        }
    }
}

@Composable
fun AutoRestartCard() {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE) }
    var autoRestart by remember { 
        mutableStateOf(sharedPref.getBoolean("auto_restart", false)) 
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自动重新启动",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "无障碍服务重连后，应用将静默地在后台自动启动并弹出提示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = autoRestart,
                onCheckedChange = { 
                    autoRestart = it
                    sharedPref.edit().putBoolean("auto_restart", it).apply()
                }
            )
        }
    }
}

private fun MainActivity.requestNotificationPermission() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                101
            )
        }
    }
}

@Composable
fun ServiceStatusCard(viewModel: RuleViewModel) {
    val context = LocalContext.current
    var isServiceBound by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    val isGlobalEnabled by viewModel.isGlobalEnabled.collectAsState()

    LaunchedEffect(Unit) {
        // Poll system service status
        while (true) {
            val enabled = isAccessibilityServiceEnabled(context)
            if (isServiceBound != enabled) isServiceBound = enabled
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Row 1: System Service Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "无障碍服务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isServiceBound) "已连接" else "未连接 (点击去开启)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isServiceBound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
                
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceBound) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (isServiceBound) "设置" else "去开启", 
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isServiceBound) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Row 2: Master Function Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动跳过功能",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isGlobalEnabled) "运行中" else "已暂停",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = isGlobalEnabled,
                    onCheckedChange = { viewModel.toggleGlobalEnabled(it) },
                    enabled = isServiceBound // Only allow toggling if service is actually running
                )
            }
        }
    }
}

@Composable
fun RuleItemCard(
    rule: ClickRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (ClickRule) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    if (showEditDialog) {
        EditRuleDialog(
            rule = rule,
            onDismiss = { showEditDialog = false },
            onConfirm = { updatedRule ->
                onEdit(updatedRule)
                showEditDialog = false
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { showEditDialog = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = rule.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!rule.groupName.isNullOrEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rule.groupName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (rule.ruleKey != null || !rule.preKeys.isNullOrEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (rule.ruleKey != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = " ${rule.ruleKey} ",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            
                            if (!rule.preKeys.isNullOrEmpty()) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = " 依赖步骤: ${rule.preKeys}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (rule.ruleKey != null) {
                                Text(
                                    text = " 起始步骤 ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = { onToggle() }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val target = if (!rule.selector.isNullOrEmpty()) rule.selector
                                 else if (!rule.targetText.isNullOrEmpty()) rule.targetText 
                                 else if (!rule.targetViewId.isNullOrEmpty()) rule.targetViewId.substringAfterLast("/") 
                                 else "坐标点击"
                    Text(
                        text = "目标: $target",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = rule.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无规则",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = "请在下拉系统快捷面板中点击录制来添加规则",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun EditRuleDialog(rule: ClickRule, onDismiss: () -> Unit, onConfirm: (ClickRule) -> Unit) {
    var editedText by remember { mutableStateOf(rule.targetText ?: "") }
    var editedSelector by remember { mutableStateOf(rule.selector ?: "") }
    var editedExclude by remember { mutableStateOf(rule.excludeCondition ?: "") }
    var editedKey by remember { mutableStateOf(rule.ruleKey?.toString() ?: "") }
    var editedPreKeys by remember { mutableStateOf(rule.preKeys ?: "") }
    var editedGroupSeed by remember { mutableStateOf("") }
    var showGkdBuilderForMain by remember { mutableStateOf(false) }
    var showGkdBuilderForExclude by remember { mutableStateOf(false) }

    if (showGkdBuilderForMain) {
        GkdBuilderDialog(
            initialGkd = editedSelector,
            onDismiss = { showGkdBuilderForMain = false },
            onConfirm = { 
                editedSelector = it
                showGkdBuilderForMain = false 
            }
        )
    }
    
    if (showGkdBuilderForExclude) {
        GkdBuilderDialog(
            initialGkd = editedExclude,
            onDismiss = { showGkdBuilderForExclude = false },
            onConfirm = { 
                editedExclude = it
                showGkdBuilderForExclude = false 
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "修改规则目标",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "无障碍服务将点击与此文本匹配的元素。例如“跳过”会匹配“跳过广告”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text("目标文本 (旧版)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = editedSelector,
                        onValueChange = { editedSelector = it },
                        label = { Text("GKD 选择器") },
                        placeholder = { Text("[vid=\"button_id\"]") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showGkdBuilderForMain = true }) {
                        Text("🪄")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Phase 12: Add Edit exclude field
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                    value = editedExclude,
                    onValueChange = { editedExclude = it },
                    label = { Text("排除条件 (GKD 选择器)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showGkdBuilderForExclude = true }) {
                            Icon(Icons.Default.Build, contentDescription = "Builder")
                        }
                    }
                )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editedKey,
                        onValueChange = { editedKey = it },
                        label = { Text("步骤 Key") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = editedPreKeys,
                        onValueChange = { editedPreKeys = it },
                        label = { Text("依赖 preKeys") },
                        modifier = Modifier.weight(1.5f),
                        singleLine = true
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editedGroupSeed,
                    onValueChange = { editedGroupSeed = it },
                    label = { Text("连招组标识 (留空保持原样)") },
                    placeholder = { Text("输入相同名称的规则将视为同一组") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            onConfirm(rule.copy(
                                targetText = editedText.takeIf { it.isNotBlank() }, 
                                selector = editedSelector.takeIf { it.isNotBlank() },
                                excludeCondition = editedExclude.takeIf { it.isNotBlank() },
                                ruleKey = editedKey.toIntOrNull(),
                                preKeys = editedPreKeys.takeIf { it.isNotBlank() },
                                groupKey = if (editedGroupSeed.isNotBlank()) (editedGroupSeed.toIntOrNull() ?: editedGroupSeed.hashCode()) else rule.groupKey
                            )) 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSubscriptionDialog(
    onDismiss: () -> Unit,
    onImportUrl: (String) -> Unit,
    onImportFile: (String) -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var fileContent by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) } // 0: URL, 1: File

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "导入 GKD 订阅",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                TabRow(selectedTabIndex = mode, indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[mode]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }) {
                    Tab(selected = mode == 0, onClick = { mode = 0 }, text = { Text("链接导入", fontSize = 12.sp) })
                    Tab(selected = mode == 1, onClick = { mode = 1 }, text = { Text("粘贴内容", fontSize = 12.sp) })
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                if (mode == 0) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("订阅链接") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = fileContent,
                        onValueChange = { fileContent = it },
                        label = { Text("粘贴 JSON/JSON5 内容") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            if (mode == 0 && urlText.isNotBlank()) onImportUrl(urlText)
                            else if (mode == 1 && fileContent.isNotBlank()) onImportFile(fileContent)
                        },
                        enabled = (mode == 0 && urlText.isNotBlank()) || (mode == 1 && fileContent.isNotBlank())
                    ) {
                        Text("导入")
                    }
                }
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

@Composable
fun AppWhitelistScreen(
    viewModel: RuleViewModel,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    val apps by viewModel.installedApps.collectAsState()
    val isLoading by viewModel.isLoadingApps.collectAsState()
    val enabledPackages by viewModel.enabledPackages.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
    }

    val filteredApps = apps.filter { 
        it.appName.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
    }

    var isHeaderVisible by remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // available.y < 0 means scrolling down (finger moving up)
                if (available.y < -5) isHeaderVisible = false
                // available.y > 0 means scrolling up (finger moving down)
                if (available.y > 5) isHeaderVisible = true
                return Offset.Zero
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
        AnimatedVisibility(
            visible = isHeaderVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("服务只对开启了白名单的应用生效。关闭不需要的应用以节省电量并防止误触。", 
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.loadInstalledApps(forceRefresh = true) }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Apps", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索包名或应用名称") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }


        if (isLoading && apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在扫描已安装的应用...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp + bottomPadding)
            ) {
                items(filteredApps) { app ->
                    val isEnabled = enabledPackages.contains(app.packageName)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.togglePackageEnabled(app.packageName, !isEnabled) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(app.appName.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { viewModel.togglePackageEnabled(app.packageName, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateRuleDialog(apps: List<cn.idiots.autoclick.util.AppInfo>, onDismiss: () -> Unit, onSave: (cn.idiots.autoclick.data.ClickRule) -> Unit) {
    var selectedApp by remember { mutableStateOf<cn.idiots.autoclick.util.AppInfo?>(null) }
    var ruleName by remember { mutableStateOf("") }
    var selector by remember { mutableStateOf("") }
    var excludeCondition by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }
    var showGkdBuilderForMain by remember { mutableStateOf(false) }
    var showGkdBuilderForExclude by remember { mutableStateOf(false) }
    
    var ruleKey by remember { mutableStateOf("") }
    var preKeys by remember { mutableStateOf("") }
    var groupSeed by remember { mutableStateOf("") }

    if (showGkdBuilderForMain) {
        GkdBuilderDialog(
            initialGkd = selector,
            onDismiss = { showGkdBuilderForMain = false },
            onConfirm = { 
                selector = it
                showGkdBuilderForMain = false 
            }
        )
    }
    
    if (showGkdBuilderForExclude) {
        GkdBuilderDialog(
            initialGkd = excludeCondition,
            onDismiss = { showGkdBuilderForExclude = false },
            onConfirm = { 
                excludeCondition = it
                showGkdBuilderForExclude = false 
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            onDismiss = { showAppPicker = false },
            onSelect = { 
                selectedApp = it
                showAppPicker = false
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text("新建手动规则", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                // Select App Button
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedApp != null) "${selectedApp!!.appName} (${selectedApp!!.packageName})" else "选择应用")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("规则名称 (例如: 跳过广告)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = selector,
                        onValueChange = { selector = it },
                        label = { Text("GKD 匹配器 / 目标文本") },
                        placeholder = { Text("[text=\"跳过\"]") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(onClick = { showGkdBuilderForMain = true }) {
                        Text("🪄")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Phase 12: Add Create exclude field
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = excludeCondition,
                        onValueChange = { excludeCondition = it },
                        label = { Text("排除条件 (可选)") },
                        placeholder = { Text("如存在则不触发: [text=\"设置\"]") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(onClick = { showGkdBuilderForExclude = true }) {
                        Text("🪄")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = ruleKey,
                        onValueChange = { ruleKey = it },
                        label = { Text("步骤 Key") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = preKeys,
                        onValueChange = { preKeys = it },
                        label = { Text("依赖 preKeys") },
                        modifier = Modifier.weight(1.5f),
                        singleLine = true
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = groupSeed,
                    onValueChange = { groupSeed = it },
                    label = { Text("连招组标识 (可选)") },
                    placeholder = { Text("输入相同名称将这些规则关联") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (selectedApp != null && selector.isNotBlank()) {
                                val isGkd = selector.startsWith("[") || selector.startsWith("@") || selector.contains("=") || selector.contains(">") || selector.contains("+")
                                onSave(cn.idiots.autoclick.data.ClickRule(
                                    packageName = selectedApp!!.packageName,
                                    appName = selectedApp!!.appName,
                                    ruleDescription = if (ruleName.isNotBlank()) ruleName else "自定义规则",
                                    selector = if (isGkd) selector else null,
                                    targetText = if (!isGkd) selector else null,
                                    excludeCondition = excludeCondition.takeIf { it.isNotBlank() },
                                    ruleKey = ruleKey.toIntOrNull(),
                                    preKeys = preKeys.takeIf { it.isNotBlank() },
                                    groupKey = if (groupSeed.isNotBlank()) (groupSeed.toIntOrNull() ?: groupSeed.hashCode()) else null,
                                    isEnabled = true
                                ))
                            }
                        },
                        enabled = selectedApp != null && selector.isNotBlank()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
fun AppPickerDialog(apps: List<cn.idiots.autoclick.util.AppInfo>, onDismiss: () -> Unit, onSelect: (cn.idiots.autoclick.util.AppInfo) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = apps.filter { 
        it.appName.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth().height(400.dp)) {
                Text("选择应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(app.appName.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

// Phase 14: Snapshot Parsing
data class SnapshotNode(
    val depth: Int,
    val className: String,
    val vid: String,
    val text: String,
    val desc: String,
    val clickable: Boolean,
    val visible: Boolean,
    val bounds: String // "[l,t][r,b]"
) {
    val isUseful: Boolean
        get() = (vid != "null" && vid.isNotBlank()) || 
                (text != "null" && text.isNotBlank()) || 
                (desc != "null" && desc.isNotBlank()) || 
                clickable

    val rect: android.graphics.Rect by lazy {
        val pattern = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        val match = pattern.find(bounds)
        if (match != null) {
            val (l, t, r, b) = match.destructured
            android.graphics.Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        } else {
            android.graphics.Rect(0, 0, 0, 0)
        }
    }
}

fun parseHierarchyDump(fileStr: String): List<SnapshotNode> {
    val nodes = mutableListOf<SnapshotNode>()
    // Format: "  [className] vid=xxx, text="xxx", desc="xxx", clickable=true, visible=true, bounds=[x,y][x,y]"
    val regex = Regex("""^(\s*)\[(.*?)\] vid=(.*?), text="(.*?)", desc="(.*?)", clickable=(.*?), visible=(.*?), bounds=(.*?)$""")
    
    fileStr.lines().forEach { line ->
        if (line.startsWith("---") || line.startsWith("Package:")) return@forEach
        val match = regex.find(line)
        if (match != null) {
            val depth = match.groupValues[1].length / 2 // "  " is 2 spaces
            val className = match.groupValues[2]
            var vid = match.groupValues[3]
            if (vid == "null") vid = ""
            var text = match.groupValues[4]
            if (text == "null") text = ""
            var desc = match.groupValues[5]
            if (desc == "null") desc = ""
            val clickable = match.groupValues[6].toBoolean()
            val visible = match.groupValues[7].toBoolean()
            val bounds = match.groupValues[8]
            
            nodes.add(SnapshotNode(depth, className.substringAfterLast("."), vid, text, desc, clickable, visible, bounds))
        }
    }
    return nodes
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotPickerDialog(
    snapshotContent: String,
    onDismiss: () -> Unit,
    onNodeSelected: (SnapshotNode) -> Unit
) {
    val allNodes = remember(snapshotContent) { parseHierarchyDump(snapshotContent) }
    // Filter out completely useless structural layouts to clean up UI
    val displayNodes = remember(allNodes) { allNodes.filter { it.isUseful } }
    
    val context = LocalContext.current
    val screenshot = remember {
        val file = File(context.getExternalFilesDir(null), "hierarchy_dump.png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } else null
    }
    var showVisual by remember { mutableStateOf(screenshot != null) }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "从无障碍快照拾取",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    if (screenshot != null) {
                        Surface(
                            tonalElevation = 2.dp,
                            shape = CircleShape,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .clickable { showVisual = false }
                                        .background(if (!showVisual) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("列表", color = if (!showVisual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .clickable { showVisual = true }
                                        .background(if (showVisual) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("视图", color = if (showVisual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                Text(
                    text = if (showVisual) "直接在画面上点击目标元素即可选中。" else "已自动过滤掉无特征的纯排版控件。点击卡片即可填入属性。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                
                if (displayNodes.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("未找到有价值的交互节点，或快照格式错误", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else if (showVisual && screenshot != null) {
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
                        val containerWidth = maxWidth
                        val containerHeight = maxHeight
                        
                        // Bitmap dimensions
                        val bWidth = screenshot.width.toFloat()
                        val bHeight = screenshot.height.toFloat()
                        
                        // Scale factors for Fit: scale content to fit within container
                        val scale = minOf(containerWidth.value / bWidth, containerHeight.value / bHeight)
                        val dx = (containerWidth.value - bWidth * scale) / 2
                        val dy = (containerHeight.value - bHeight * scale) / 2

                        Image(
                            bitmap = screenshot,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Interactive Overlays
                        displayNodes.forEach { node ->
                            val r = node.rect
                            if (r.width() > 0 && r.height() > 0) {
                                Box(
                                    modifier = Modifier
                                        .offset(
                                            x = (r.left * scale + dx).dp,
                                            y = (r.top * scale + dy).dp
                                        )
                                        .size(
                                            width = (r.width() * scale).dp,
                                            height = (r.height() * scale).dp
                                        )
                                        .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(1.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                                        .clickable { onNodeSelected(node) }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayNodes.size) { index ->
                            val node = displayNodes[index]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNodeSelected(node) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(node.className, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                        if (node.clickable) {
                                            Text("可点击", fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (node.text.isNotBlank()) Text("text: \"${node.text}\"", style = MaterialTheme.typography.bodySmall)
                                    if (node.desc.isNotBlank()) Text("desc: \"${node.desc}\"", style = MaterialTheme.typography.bodySmall)
                                    if (node.vid.isNotBlank()) Text("vid: ${node.vid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                    Text("bounds: ${node.bounds}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                }
            }
        }
    }
}

// Phase 13: GkdBuilderDialog component
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GkdBuilderDialog(
    initialGkd: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // Basic fields
    var vid by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var textMatch by remember { mutableStateOf("=") } // =, *=, ^=, $=
    var desc by remember { mutableStateOf("") }
    var descMatch by remember { mutableStateOf("=") }
    var className by remember { mutableStateOf("") }
    
    // Booleans (-1 = unset, 1 = true, 0 = false)
    var clickable by remember { mutableStateOf(-1) }
    var visibleToUser by remember { mutableStateOf(-1) }
    
    // Parsing existing code if possible (super basic heuristic)
    LaunchedEffect(initialGkd) {
        if (initialGkd.isNotBlank()) {
            val vidMatch = Regex("""vid="([^"]+)"""").find(initialGkd)
            if (vidMatch != null) vid = vidMatch.groupValues[1]
            
            val idMatch = Regex("""\[id="([^"]+)"""").find(initialGkd)
            if (idMatch != null) id = idMatch.groupValues[1]

            val textMatchRx = Regex("""text([=*^$]+)="([^"]+)"""").find(initialGkd)
            if (textMatchRx != null) {
                textMatch = textMatchRx.groupValues[1]
                text = textMatchRx.groupValues[2]
            }

            val descMatchRx = Regex("""desc([=*^$]+)="([^"]+)"""").find(initialGkd)
            if (descMatchRx != null) {
                descMatch = descMatchRx.groupValues[1]
                desc = descMatchRx.groupValues[2]
            }

            val classMatch = Regex("""^([\w.]+)\[""").find(initialGkd)
            if (classMatch != null) className = classMatch.groupValues[1]
            
            if (initialGkd.contains("[clickable=true]")) clickable = 1
            if (initialGkd.contains("[clickable=false]")) clickable = 0
            
            if (initialGkd.contains("[visibleToUser=true]")) visibleToUser = 1
            if (initialGkd.contains("[visibleToUser=false]")) visibleToUser = 0
        }
    }

    // Generate output string
    val generatedGkd = remember(vid, id, text, textMatch, desc, descMatch, className, clickable, visibleToUser) {
        val parts = mutableListOf<String>()
        if (vid.isNotBlank()) parts.add("""vid="$vid"""")
        if (id.isNotBlank()) parts.add("""id="$id"""")
        if (text.isNotBlank()) parts.add("""text$textMatch"$text"""")
        if (desc.isNotBlank()) parts.add("""desc$descMatch"$desc"""")
        if (clickable != -1) parts.add("clickable=${clickable == 1}")
        if (visibleToUser != -1) parts.add("visibleToUser=${visibleToUser == 1}")
        
        val classPrefix = if (className.isNotBlank()) className else ""
        if (parts.isEmpty()) classPrefix else classPrefix + parts.joinToString("][", prefix = "[", postfix = "]")
    }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    text = "🪄 GKD 可视化构造器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "无需懂代码，填入所需条件即可自动生成强大的规则表达式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
                
                // Live Preview Block
                Surface(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = generatedGkd.ifEmpty { "/* 请在下方填写条件 */" },
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 14.sp
                            ),
                            color = if (generatedGkd.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                // Phase 14: Snapshot Picker Launch Button
                var showSnapshotPicker by remember { mutableStateOf(false) }
                if (showSnapshotPicker) {
                    val context = LocalContext.current
                    val file = java.io.File(context.getExternalFilesDir(null), "hierarchy_dump.txt")
                    if (file.exists()) {
                        SnapshotPickerDialog(
                            snapshotContent = file.readText(),
                            onDismiss = { showSnapshotPicker = false },
                            onNodeSelected = { node ->
                                if (node.vid.isNotBlank()) vid = node.vid
                                if (node.text.isNotBlank()) {
                                    textMatch = "="
                                    text = node.text
                                }
                                if (node.desc.isNotBlank()) {
                                    descMatch = "="
                                    desc = node.desc
                                }
                                if (node.className.isNotBlank()) className = node.className
                                if (node.clickable) clickable = 1
                                if (node.visible) visibleToUser = 1
                                showSnapshotPicker = false
                            }
                        )
                    } else {
                        android.widget.Toast.makeText(context, "未找到快照记录。请先在无障碍录制菜单中“保存页面快照”", android.widget.Toast.LENGTH_LONG).show()
                        showSnapshotPicker = false
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val fileExists = java.io.File(LocalContext.current.getExternalFilesDir(null), "hierarchy_dump.txt").exists()
                    Text(
                        text = if (fileExists) "发现本地快照，点击提取元素" else "无可用快照，请手动填入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedButton(
                        onClick = { showSnapshotPicker = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        enabled = fileExists
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("从快照中拾取", fontSize = 12.sp)
                    }
                }
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = vid,
                            onValueChange = { vid = it },
                            label = { Text("短 ID (vid)") },
                            placeholder = { Text("例如: tv_skip") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    
                    item {
                        OutlinedTextField(
                            value = id,
                            onValueChange = { id = it },
                            label = { Text("完整 ID (包含包名)") },
                            placeholder = { Text("例如: com.app:id/tv_skip") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    item {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            var expanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(0.3f)) {
                                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(when(textMatch) { "*=" -> "包含"; "^=" -> "开头"; "$=" -> "结尾"; else -> "精确" })
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("精确 (=)") }, onClick = { textMatch = "="; expanded = false })
                                    DropdownMenuItem(text = { Text("包含 (*=)") }, onClick = { textMatch = "*="; expanded = false })
                                    DropdownMenuItem(text = { Text("开头 (^=)") }, onClick = { textMatch = "^="; expanded = false })
                                    DropdownMenuItem(text = { Text("结尾 (\$=)") }, onClick = { textMatch = "$="; expanded = false })
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = { Text("文本内容 (text)") },
                                placeholder = { Text("例如: 跳过") },
                                modifier = Modifier.weight(0.7f),
                                singleLine = true
                            )
                        }
                    }

                    item {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            var expanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(0.3f)) {
                                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(when(descMatch) { "*=" -> "包含"; "^=" -> "开头"; "$=" -> "结尾"; else -> "精确" })
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("精确 (=)") }, onClick = { descMatch = "="; expanded = false })
                                    DropdownMenuItem(text = { Text("包含 (*=)") }, onClick = { descMatch = "*="; expanded = false })
                                    DropdownMenuItem(text = { Text("开头 (^=)") }, onClick = { descMatch = "^="; expanded = false })
                                    DropdownMenuItem(text = { Text("结尾 (\$=)") }, onClick = { descMatch = "$="; expanded = false })
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = desc,
                                onValueChange = { desc = it },
                                label = { Text("内容描述 (desc)") },
                                placeholder = { Text("图像按钮常用, 类似 alt") },
                                modifier = Modifier.weight(0.7f),
                                singleLine = true
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = className,
                            onValueChange = { className = it },
                            label = { Text("控件类型 (className)") },
                            placeholder = { Text("例如: ImageView") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    item {
                        Text("状态特征", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TriStateDropdown("可以点击", clickable) { clickable = it }
                            TriStateDropdown("真伪可见", visibleToUser) { visibleToUser = it }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onConfirm(generatedGkd) },
                        enabled = generatedGkd.isNotBlank()
                    ) {
                        Text("完成填入")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(bottomPadding: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE) }
    
    var globalCooldown by remember { mutableFloatStateOf(sharedPref.getInt("global_cooldown", 5000).toFloat()) }
    var globalMaxClicks by remember { mutableFloatStateOf(sharedPref.getInt("global_max_clicks", 2).toFloat()) }
    var elementCooldown by remember { mutableFloatStateOf(sharedPref.getInt("element_cooldown", 5000).toFloat()) }
    var hideFromRecents by remember { mutableStateOf(sharedPref.getBoolean("hide_from_recents", false)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item {
            Text("性能与限流", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingSlider(
                        label = "全局冷却时间 (ms)",
                        value = globalCooldown,
                        range = 500f..10000f,
                        onValueChange = { 
                            globalCooldown = it
                            sharedPref.edit().putInt("global_cooldown", it.toInt()).apply()
                        },
                        valueDisplay = "${globalCooldown.toInt()}ms"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingSlider(
                        label = "5秒内最大点击次数 (全局)",
                        value = globalMaxClicks,
                        range = 1f..10f,
                        onValueChange = { 
                            globalMaxClicks = it
                            sharedPref.edit().putInt("global_max_clicks", it.toInt()).apply()
                        },
                        valueDisplay = "${globalMaxClicks.toInt()}次"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingSlider(
                        label = "同一元素冷却时间 (ms)",
                        value = elementCooldown,
                        range = 500f..10000f,
                        onValueChange = { 
                            elementCooldown = it
                            sharedPref.edit().putInt("element_cooldown", it.toInt()).apply()
                        },
                        valueDisplay = "${elementCooldown.toInt()}ms"
                    )
                }
            }
        }

        item {
            Text("安全与隐私", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐藏最近任务列表", style = MaterialTheme.typography.bodyLarge)
                        Text("开启后应用将不会出现在多任务视图中，保护隐私。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Switch(
                        checked = hideFromRecents,
                        onCheckedChange = { 
                            hideFromRecents = it
                            sharedPref.edit().putBoolean("hide_from_recents", it).apply()
                            // Note: Effect takes place on next start or manual set
                        }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "提示：所有限流修改将实时生效（除了某些需要重启无障碍服务的情况）。如果你发现点击响应过慢，请尝试调低冷却时间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        content()
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueDisplay: String
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueDisplay, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun TriStateDropdown(label: String, state: Int, onStateChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayObj = when(state) { 1 -> "是 (true)"; 0 -> "否 (false)"; else -> "不限" }
    
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(displayObj)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("不限") }, onClick = { onStateChange(-1); expanded = false })
            DropdownMenuItem(text = { Text("是 (true)") }, onClick = { onStateChange(1); expanded = false })
            DropdownMenuItem(text = { Text("否 (false)") }, onClick = { onStateChange(0); expanded = false })
        }
    }
}
