package cn.idiots.autoclick.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import cn.idiots.autoclick.AutoClickApplication
import cn.idiots.autoclick.data.ClickRule
import cn.idiots.autoclick.data.ClickRuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import cn.idiots.autoclick.util.GkdSelector
import android.graphics.Path
import android.view.Display
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.util.LruCache
import android.graphics.Bitmap
import java.io.FileOutputStream
import java.io.File

class AutoClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: ClickRuleRepository
    private var currentActivityId: String? = null
    
    // Safety Debounce: Maximize battery life by throttling UI tree traversals per app
    private val lastProcessTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Safety Throttling: Map of RuleID to list of trigger timestamps
    private val ruleTriggerHistory = java.util.concurrent.ConcurrentHashMap<Int, MutableList<Long>>()

    // Safely cache rules in memory to prevent SQLite thrashing on thousands of accessibility events
    private var rulesMap: Map<String, List<ClickRule>> = emptyMap()
    private var globalRules: List<ClickRule> = emptyList()
    
    // Whitelist cache
    private var enabledPackages: Set<String> = emptySet()
    
    // System Launchers Cache
    private var launcherPackages: Set<String> = emptySet()
    
    // Global switch state
    private var isGlobalEnabled: Boolean = true

    // Cache pre-compiled ASTs for GKD selectors to avoid regex/string parsing on every accessibility event
    private val selectorCache = LruCache<String, GkdSelector>(500)

    // Stage Tracking for Multi-stage Clicks
    // Key: groupKey (from rule), Value: Set of satisfied ruleKeys
    private val satisfiedKeys = java.util.concurrent.ConcurrentHashMap<Int, MutableSet<Int>>()
    private var lastSequenceApp: String? = null
    private var lastSequenceActivity: String? = null

    // Per-Element Cooling: Map of "RuleId_NodeFingerprint" to last trigger timestamp
    private val nodeTriggerHistory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        var instance: AutoClickAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = (application as AutoClickApplication).repository
        repository.syncRecordingState()
        launcherPackages = getLauncherPackages()
        
        serviceScope.launch {
            repository.allRules.collect { rules ->
                val activeRules = rules.filter { it.isEnabled }
                val newMap = java.util.concurrent.ConcurrentHashMap<String, List<ClickRule>>()
                newMap.putAll(activeRules.filter { it.packageName != "*" }.groupBy { it.packageName })
                rulesMap = newMap
                globalRules = activeRules.filter { it.packageName == "*" }
            }
        }
        
        serviceScope.launch {
            repository.enabledPackages.collect { packages ->
                enabledPackages = packages
            }
        }

        serviceScope.launch {
            repository.isGlobalEnabled.collect { enabled ->
                isGlobalEnabled = enabled
            }
        }
        
        Log.d("AutoClicker", "Service Connected")

        // Auto-Restart Logic: If enabled, relaunch the app to ensure it's alive
        val sharedPref = getSharedPreferences("autoclick_prefs", android.content.Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("auto_restart", false)) {
            Toast.makeText(this, "极速连点：自动重连成功", Toast.LENGTH_SHORT).show()
            Log.d("AutoClicker", "Auto-reconnect Toast shown")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // GLOBAL MASTER SWITCH: If function is disabled via Dashboard, stop all processing immediately
        if (!isGlobalEnabled) return

        val packageName = event.packageName?.toString() ?: return

        // APP WHITELIST FILTERING: 
        // 1. Explicitly ignore the AutoClicker app itself to prevent self-triggering loops while editing rules
        if (packageName == "cn.idiots.autoclick") return
        
        // 2. Explicitly ignore System UI, Android OS, and ALL Desktop Launchers
        // This prevents catastrophic infinite loops on the home screen if a global rule matches a generic button
        if (packageName == "com.android.systemui" || packageName == "android" || launcherPackages.contains(packageName)) {
            return
        }
        
        // 3. If the app is not in the user's enabled whitelist, drop the event immediately.
        // This brings background CPU usage to literal zero for unselected apps.
        if (!enabledPackages.contains(packageName)) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (!className.isNullOrEmpty() && !className.startsWith("android.widget.") && !className.startsWith("android.view.")) {
                currentActivityId = className
                Log.d("AutoClicker", "Current Activity Updated: $currentActivityId")
            }
        }
        
        // Fallback: If currentActivityId is still null, try to infer it from root window
        if (currentActivityId == null) {
            rootInActiveWindow?.let {
                val className = it.className?.toString()
                if (!className.isNullOrEmpty() && className.contains("Activity")) {
                    currentActivityId = className
                }
            }
        }

        // REMOVED redundant sync to avoid race conditions with SharedPreferences apply()
        
        // 1. Check if we are in Recording Mode
        val isRecording = repository.isRecording.value
        if (isRecording) {
            Log.d("AutoClicker", "Recording ON, event: ${AccessibilityEvent.eventTypeToString(event.eventType)} from $packageName")
        }

        if (isRecording && (
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
            )) {
            // CRITICAL FIX: Ignore clicks on System UI (like the Quick Settings record button itself)
            val ignoredPackages = listOf("com.android.systemui", "android", "cn.idiots.autoclick")
            if (ignoredPackages.contains(packageName)) {
                Log.d("AutoClicker", "Ignoring event from $packageName")
                return
            }
            
            Toast.makeText(this, "正在记录 $packageName 的节点...", Toast.LENGTH_SHORT).show()
            recordRule(event, packageName)
            return
        }

        // 2. Perform Auto Click
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // Critical Battery Optimization: Throttle UI tree scanning to max 1 per 400ms per app
            val now = System.currentTimeMillis()
            val lastTime = lastProcessTime[packageName] ?: 0L
            if (now - lastTime < 400) {
                return // Drop the event to save CPU
            }
            lastProcessTime[packageName] = now

            serviceScope.launch {
                matchRules(packageName)
            }
        }
    }

    private suspend fun matchRules(packageName: String) {
        val rootNode = rootInActiveWindow ?: return
        val rulesForApp = rulesMap[packageName] ?: emptyList()
        val rules = rulesForApp + globalRules
        if (rules.isEmpty()) return

        var foundMatch = false
        
        // Reset sequence state if app or activity changed
        if (lastSequenceApp != packageName || lastSequenceActivity != currentActivityId) {
            satisfiedKeys.clear()
            lastSequenceApp = packageName
            lastSequenceActivity = currentActivityId
        }

        for (rule in rules) {
            // Filter by Activity ID if rule specifies it
            if (!rule.activityIds.isNullOrEmpty()) {
                val current = currentActivityId ?: ""
                if (current.isEmpty()) continue
                if (!rule.activityIds.contains(current)) continue
            }

            // Safety Throttling: Max 2 triggers per 5 seconds per rule
            if (!canTriggerRule(rule.id)) {
                continue
            }

            // Multi-stage Check: Check if preKeys (if any) are satisfied
            if (!rule.preKeys.isNullOrEmpty() && rule.groupKey != null) {
                val groupSatisfied = satisfiedKeys[rule.groupKey] ?: emptySet<Int>()
                val requiredKeys = rule.preKeys.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (!requiredKeys.all { groupSatisfied.contains(it) }) {
                    continue // Sequence requirement not met
                }
            }

            val targetNode = findMatch(rootNode, rule)
            if (targetNode != null) {
                val fingerprint = getNodeFingerprint(targetNode)
                if (canTriggerElement(rule.id, fingerprint)) {
                    if (performClick(targetNode)) {
                        recordRuleTrigger(rule.id)
                        recordElementTrigger(rule.id, fingerprint)
                        
                        // Record satisfied key if this rule has a key
                        if (rule.ruleKey != null && rule.groupKey != null) {
                            val groupSet = satisfiedKeys.getOrPut(rule.groupKey) { 
                                java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>()) 
                            }
                            groupSet.add(rule.ruleKey)
                            Log.d("AutoClicker", "Sequence Satisfied: Key ${rule.ruleKey} for Group ${rule.groupKey}")
                        }
                        
                        Log.d("AutoClicker", "SUCCESS: Triggered rule [${rule.groupName ?: "Default"}] for $packageName")
                        serviceScope.launch {
                            repository.logClick(rule)
                        }
                        foundMatch = true
                        break 
                    }
                }
            }
        }
    }

    private fun recordRule(event: AccessibilityEvent, packageName: String) {
        val node = event.source ?: return
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        val viewId = node.viewIdResourceName
        
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val boundsString = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"

        val appName = getAppName(packageName)

        // Generate a robust selector based on what we captured
        val selectorBuilder = StringBuilder()
        if (!viewId.isNullOrEmpty()) {
            selectorBuilder.append("[vid=\"${viewId.substringAfterLast("/")}\"]")
        }
        if (!text.isNullOrEmpty()) {
            // If it's a very long text, maybe it's dynamic, but let's take a substring for robustness
            val safeText = if (text.length > 10) text.take(5) else text
            selectorBuilder.append("[text=\"$safeText\"]")
        }
        if (selectorBuilder.isEmpty()) {
            // Fallback to bounds if nothing else is available (e.g. anonymous image button)
            selectorBuilder.append("[bounds=\"$boundsString\"]")
        }

        val newRule = ClickRule(
            packageName = packageName,
            appName = appName,
            targetText = text,
            targetViewId = viewId,
            boundsInScreen = boundsString,
            selector = selectorBuilder.toString(),
            activityIds = currentActivityId
        )

        serviceScope.launch {
            repository.insertRule(newRule)
            serviceScope.launch(Dispatchers.Main) {
                // Must stop recording in main thread or sync it appropriately
                repository.stopRecording()
                Toast.makeText(this@AutoClickAccessibilityService, "$appName 的规则已记录", Toast.LENGTH_SHORT).show()
                // Broaden support: requestListeningState was added in API 24 (Android 7.0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        TileService.requestListeningState(this@AutoClickAccessibilityService, 
                            ComponentName(this@AutoClickAccessibilityService, RecordTileService::class.java))
                    } catch (e: Exception) {
                        Log.e("AutoClicker", "Failed to update Record tile", e)
                    }
                }
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun findMatch(node: AccessibilityNodeInfo, rule: ClickRule): AccessibilityNodeInfo? {
        // Phase 12: Rule Exclusion Condition Guard
        if (!rule.excludeCondition.isNullOrEmpty()) {
            try {
                var excludeSelector: GkdSelector? = null
                synchronized(selectorCache) {
                    excludeSelector = selectorCache.get("EXCLUSION_${rule.excludeCondition}")
                    if (excludeSelector == null) {
                        try {
                            excludeSelector = GkdSelector(rule.excludeCondition)
                            selectorCache.put("EXCLUSION_${rule.excludeCondition}", excludeSelector)
                        } catch (e: Exception) {
                            Log.e("AutoClicker", "GKD exclusion selector creation failed: ${rule.excludeCondition}", e)
                        }
                    }
                }
                
                // If the exclusion condition matches ANYTHING on the screen, abort this rule
                if (excludeSelector != null && excludeSelector!!.find(node) != null) {
                    Log.d("AutoClicker", "Rule [${rule.groupName}] suppressed by exclusion condition: ${rule.excludeCondition}")
                    return null
                }
            } catch (e: Exception) {
                Log.e("AutoClicker", "GKD exclusion selector execution failed: ${rule.excludeCondition}", e)
            }
        }

        // 1. Try GKD Selector (The Modern Way)
        if (!rule.selector.isNullOrEmpty()) {
            try {
                var selector: GkdSelector? = null
                synchronized(selectorCache) {
                    selector = selectorCache.get(rule.selector)
                    if (selector == null) {
                        try {
                            selector = GkdSelector(rule.selector)
                            selectorCache.put(rule.selector, selector)
                        } catch (e: Exception) {
                            Log.e("AutoClicker", "GKD Selector creation failed: ${rule.selector}", e)
                        }
                    }
                }
                
                if (selector != null) {
                    val foundNode = selector!!.find(node)
                    if (foundNode != null) return foundNode
                }
            } catch (e: Exception) {
                Log.e("AutoClicker", "GKD Selector execution failed: ${rule.selector}", e)
            }
        }

        // 2. Legacy Fallbacks (In case selector matching fails or is empty)
        // Find by View ID
        if (!rule.targetViewId.isNullOrEmpty()) {
            val nodes = node.findAccessibilityNodeInfosByViewId(rule.targetViewId)
            if (nodes.isNotEmpty()) return nodes[0]
        }

        // Find by Text
        if (!rule.targetText.isNullOrEmpty()) {
             val found = dnsSearchNodeByText(node, rule.targetText)
             if (found != null) return found
        }

        // Ultimate Anti-Obfuscation Fallback: Find by physical screen coordinates
        if (!rule.boundsInScreen.isNullOrEmpty()) {
            val boundsArray = rule.boundsInScreen.split(",")
            if (boundsArray.size == 4) {
                try {
                    val targetRect = Rect(boundsArray[0].toInt(), boundsArray[1].toInt(), boundsArray[2].toInt(), boundsArray[3].toInt())
                    if (targetRect.width() > 0 && targetRect.height() > 0) {
                        val found = dnsSearchNodeByBounds(node, targetRect)
                        if (found != null) return found
                    }
                } catch (e: Exception) { }
            }
        }

        return null
    }
    
    private fun dnsSearchNodeByText(root: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(root)
        
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString()
            if (nodeText != null && nodeText.contains(targetText, ignoreCase = true)) {
                // Found it, but don't recycle yet as it's the result
                // Optional: recycle others in stack? Too complex.
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun dnsSearchNodeByBounds(root: AccessibilityNodeInfo, targetRect: Rect): AccessibilityNodeInfo? {
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(root)
        val tolerance = 30
        
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val nodeRect = Rect()
            node.getBoundsInScreen(nodeRect)
            
            val isMatch = Math.abs(nodeRect.left - targetRect.left) <= tolerance &&
                          Math.abs(nodeRect.top - targetRect.top) <= tolerance &&
                          Math.abs(nodeRect.right - targetRect.right) <= tolerance &&
                          Math.abs(nodeRect.bottom - targetRect.bottom) <= tolerance

            if (isMatch) return node
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        // Anti-obfuscation: Sometimes the target node isn't clickable, but its parent or grandparent is.
        // We traverse up to 10 levels of parents looking for a clickable container.
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current.parent
            depth++
        }
        
        // Final Fallback: Physical Gesture Click (Coordinate-based)
        // This handles nodes that are not clickable via accessibility but represent standard UI buttons.
        return dispatchGestureClick(node)
    }

    private fun dispatchGestureClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        
        val path = Path()
        path.moveTo(x, y)
        
        return try {
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e("AutoClicker", "Gesture click failed at ($x, $y)", e)
            false
        }
    }

    override fun onInterrupt() {}

    fun dumpHierarchy() {
        var root = rootInActiveWindow
        
        // If we captured SystemUI (likely the shade still closing), try to find the actual app window
        if (root == null || root.packageName == "com.android.systemui") {
            val windows = windows
            for (window in windows) {
                val windowRoot = window.root
                if (windowRoot != null && windowRoot.packageName != "com.android.systemui") {
                    root = windowRoot
                    break
                }
            }
        }
        
        if (root == null) {
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(this@AutoClickAccessibilityService, "无法找到目标应用窗口", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val sb = StringBuilder()
        sb.append("--- UI HIERARCHY DUMP ---\n")
        sb.append("Package: ${root.packageName}\n")
        buildDump(root, sb)
        
        val dumpString = sb.toString()
        Log.d("HierarchyDump", dumpString)
        
        // Save to file
        try {
            val file = java.io.File(getExternalFilesDir(null), "hierarchy_dump.txt")
            file.writeText(dumpString)
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(this@AutoClickAccessibilityService, "视图快照已保存至: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                Log.i("AutoClicker", "Dump file path: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("HierarchyDump", "Failed to save dump", e)
        }

        // Capture screenshot if API 30+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.hardwareBuffer, screenshotResult.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false) // Hardware-backed bitmaps can't be compressed directly sometimes
                    if (bitmap != null) {
                        try {
                            val screenFile = java.io.File(getExternalFilesDir(null), "hierarchy_dump.png")
                            FileOutputStream(screenFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Log.i("AutoClicker", "Screenshot saved: ${screenFile.absolutePath}")
                            serviceScope.launch(Dispatchers.Main) {
                                Toast.makeText(this@AutoClickAccessibilityService, "截图快照捕获成功", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("AutoClicker", "Failed to save screenshot", e)
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.e("AutoClicker", "Screenshot failed: $errorCode")
                    serviceScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@AutoClickAccessibilityService, "截图失败: 错误码 $errorCode", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun buildDump(root: AccessibilityNodeInfo, sb: StringBuilder) {
        val stack = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(Pair(root, 0))
        val visited = mutableSetOf<Int>() // Use identity hash codes if possible, or just trust depth

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeAt(stack.size - 1)
            
            if (depth > 100) {
                continue
            }

            val indent = "  ".repeat(depth)
            val vid = node.viewIdResourceName?.substringAfterLast("/") ?: "null"
            val text = node.text?.toString()?.replace("\n", " ") ?: "null"
            val desc = node.contentDescription?.toString()?.replace("\n", " ") ?: "null"
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            sb.append("$indent[${node.className}] vid=$vid, text=\"$text\", desc=\"$desc\", clickable=${node.isClickable}, visible=${node.isVisibleToUser}, bounds=${bounds.toShortString()}\n")
            
            // Push children in reverse order to maintain correct DFS order in text
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(Pair(child, depth + 1))
                }
            }
        }
    }

    private fun canTriggerRule(ruleId: Int): Boolean {
        val prefs = getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE)
        val cooldownMs = prefs.getInt("global_cooldown", 5000)
        val maxClicks = prefs.getInt("global_max_clicks", 2)
        
        var canTrigger = false
        val now = System.currentTimeMillis()
        ruleTriggerHistory.compute(ruleId) { _, history ->
            val currentHistory = history ?: mutableListOf()
            val iterator = currentHistory.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next() > cooldownMs) {
                    iterator.remove()
                }
            }
            canTrigger = currentHistory.size < maxClicks
            currentHistory
        }
        return canTrigger
    }

    private fun getNodeFingerprint(node: AccessibilityNodeInfo): String {
        val vid = node.viewIdResourceName ?: "no_vid"
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: "no_text"
        val className = node.className?.toString() ?: "no_class"
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${vid}_${text}_${className}_${bounds.toShortString()}"
    }

    private fun canTriggerElement(ruleId: Int, fingerprint: String): Boolean {
        val prefs = getSharedPreferences("autoclick_prefs", Context.MODE_PRIVATE)
        val elementCooldownMs = prefs.getInt("element_cooldown", 5000)
        
        val key = "${ruleId}_$fingerprint"
        val lastTime = nodeTriggerHistory[key] ?: 0L
        return (System.currentTimeMillis() - lastTime) > elementCooldownMs
    }

    private fun recordElementTrigger(ruleId: Int, fingerprint: String) {
        val key = "${ruleId}_$fingerprint"
        nodeTriggerHistory[key] = System.currentTimeMillis()
        
        // Cleanup old entries occasionally to prevent memory leaks
        if (nodeTriggerHistory.size > 1000) {
            val now = System.currentTimeMillis()
            val it = nodeTriggerHistory.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > 10000) it.remove()
            }
        }
    }

    private fun recordRuleTrigger(ruleId: Int) {
        val now = System.currentTimeMillis()
        ruleTriggerHistory.compute(ruleId) { _, history ->
            val currentHistory = history ?: mutableListOf()
            currentHistory.add(now)
            currentHistory
        }
    }
    
    private fun getLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
    }
}
