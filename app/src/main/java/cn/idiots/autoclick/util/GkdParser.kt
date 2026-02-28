package cn.idiots.autoclick.util

import cn.idiots.autoclick.data.ClickRule
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

object GkdParser {
    fun parse(jsonInput: String, subUrl: String? = null): List<ClickRule> {
        val ruleList = mutableListOf<ClickRule>()
        try {
            // Clean JSON5 features (basics: comments, single quotes parsing)
            val cleanedJson = cleanJson5(jsonInput)
            val root = if (cleanedJson.trim().startsWith("[")) {
                // Standalone list of apps
                JSONObject().put("apps", JSONArray(cleanedJson))
            } else {
                JSONObject(cleanedJson)
            }

            val subName = root.optString("name", "Imported Subscription")
            
            // 1. Parse Global Groups
            val globalGroups = root.optJSONArray("globalGroups") ?: JSONArray()
            parseGroups(globalGroups, "global", "Global App", subName, subUrl, ruleList, isGlobal = true)

            // 2. Parse per-app rules
            val appsArray = root.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until appsArray.length()) {
                val appObj = appsArray.optJSONObject(i) ?: continue
                val packageName = appObj.optString("id")
                if (packageName.isEmpty() || packageName == "null") continue
                val appName = appObj.optString("name", packageName)
                
                val groupsArray = appObj.optJSONArray("groups") ?: JSONArray()
                parseGroups(groupsArray, packageName, appName, subName, subUrl, ruleList, isGlobal = false)
            }
        } catch (e: Exception) {
            Log.e("GkdParser", "Failed to parse GKD JSON", e)
        }
        return ruleList
    }

    private fun parseGroups(
        groupsArray: JSONArray,
        defaultPackageName: String,
        defaultAppName: String,
        subName: String,
        subUrl: String?,
        ruleList: MutableList<ClickRule>,
        isGlobal: Boolean
    ) {
        for (j in 0 until groupsArray.length()) {
            val groupObj = groupsArray.optJSONObject(j) ?: continue
            val groupName = groupObj.optString("name", "Default Group")
            val groupDesc = groupObj.optString("desc", groupName)
            
            val rulesArray = groupObj.optJSONArray("rules") ?: JSONArray()
            
            // Generate a deterministic groupKey for sequence isolation
            val groupKey = "${subName}:${defaultPackageName}:${groupName}".hashCode()

            // Sometimes rules is a single string or object
            if (rulesArray.length() == 0 && groupObj.has("rules")) {
                val rulesObj = groupObj.opt("rules")
                if (rulesObj is String) {
                    addRule(ruleList, defaultPackageName, defaultAppName, rulesObj, null, groupName, groupDesc, subName, subUrl, isGlobal, null, null, groupKey, null)
                    continue
                } else if (rulesObj is JSONObject) {
                    val matchesList = parseStringOrArray(rulesObj.opt("matches"))
                    val excludeMatches = rulesObj.optString("excludeMatches")
                    val key = if (rulesObj.has("key")) rulesObj.optInt("key") else null
                    val preKeysList = parseStringOrArray(rulesObj.opt("preKeys"))
                    val preKeysStr = if (preKeysList.isNotEmpty()) preKeysList.joinToString(",") else null
                    for (match in matchesList) {
                        addRule(ruleList, defaultPackageName, defaultAppName, match, excludeMatches, groupName, groupDesc, subName, subUrl, isGlobal, null, key, groupKey, preKeysStr)
                    }
                    continue
                }
            }

            for (k in 0 until rulesArray.length()) {
                val ruleObj = rulesArray.opt(k)
                if (ruleObj is JSONObject) {
                    val matchesList = parseStringOrArray(ruleObj.opt("matches"))
                    val excludeMatches = ruleObj.optString("excludeMatches")
                    val activityIdsList = parseStringOrArray(ruleObj.opt("activityIds"))
                    val activityIdsStr = if (activityIdsList.isNotEmpty()) activityIdsList.joinToString(",") else null
                    val key = if (ruleObj.has("key")) ruleObj.optInt("key") else null
                    val preKeysList = parseStringOrArray(ruleObj.opt("preKeys"))
                    val preKeysStr = if (preKeysList.isNotEmpty()) preKeysList.joinToString(",") else null
                    
                    for (match in matchesList) {
                        addRule(ruleList, defaultPackageName, defaultAppName, match, excludeMatches, groupName, groupDesc, subName, subUrl, isGlobal, activityIdsStr, key, groupKey, preKeysStr)
                    }
                } else if (ruleObj is String) {
                    addRule(ruleList, defaultPackageName, defaultAppName, ruleObj, null, groupName, groupDesc, subName, subUrl, isGlobal, null, null, groupKey, null)
                }
            }
        }
    }

    private fun parseStringOrArray(obj: Any?): List<String> {
        val result = mutableListOf<String>()
        if (obj is String) {
            result.add(obj)
        } else if (obj is JSONArray) {
            for (i in 0 until obj.length()) {
                val str = obj.optString(i)
                if (str.isNotEmpty()) result.add(str)
            }
        }
        return result
    }

    private fun addRule(
        ruleList: MutableList<ClickRule>,
        packageName: String,
        appName: String,
        matches: String,
        excludeMatches: String?,
        groupName: String,
        desc: String,
        subName: String,
        subUrl: String?,
        isGlobal: Boolean,
        activityIds: String? = null,
        ruleKey: Int? = null,
        groupKey: Int? = null,
        preKeys: String? = null
    ) {
        var finalSelector = matches
        if (matches.isEmpty()) return
        
        // Phase 12: Map GKD's excludeMatches to our engine's excludeCondition
        ruleList.add(
            ClickRule(
                packageName = if (isGlobal) "*" else packageName,
                appName = if (isGlobal) "Global Configuration" else appName,
                selector = finalSelector,
                activityIds = activityIds,
                groupName = if (isGlobal) "[Global] $groupName" else groupName,
                ruleDescription = desc,
                isSubscription = true,
                subscriptionName = subName,
                subscriptionUrl = subUrl,
                excludeCondition = excludeMatches?.takeIf { it.isNotBlank() },
                ruleKey = ruleKey,
                groupKey = groupKey,
                preKeys = preKeys
            )
        )
    }

    // A robust state-machine JSON5 to JSON cleaner
    private fun cleanJson5(json5: String): String {
        val sb = StringBuilder()
        var i = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inSingleLineComment = false
        var inMultiLineComment = false

        while (i < json5.length) {
            val c = json5[i]
            val nextC = if (i + 1 < json5.length) json5[i + 1] else '\u0000'

            // Handle escaping within strings first
            if ((inSingleQuote || inDoubleQuote) && c == '\\') {
                sb.append(c)
                sb.append(nextC)
                i += 2
                continue
            }

            if (inSingleLineComment) {
                if (c == '\n' || c == '\r') {
                    inSingleLineComment = false
                    sb.append(c)
                }
                i++
                continue
            }
            if (inMultiLineComment) {
                if (c == '*' && nextC == '/') {
                    inMultiLineComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == '/' && nextC == '/') {
                    inSingleLineComment = true
                    i += 2
                    continue
                }
                if (c == '/' && nextC == '*') {
                    inMultiLineComment = true
                    i += 2
                    continue
                }
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                sb.append(c)
                i++
                continue
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                sb.append('"') // Swap outer single quote to double
                i++
                continue
            }

            if (inSingleQuote) {
                if (c == '"') {
                    sb.append("\\\"") // Escape double quotes inside single quotes
                } else {
                    sb.append(c)
                }
                i++
                continue
            }

            sb.append(c)
            i++
        }

        var result = sb.toString()
        // Fix unquoted keys mapping. Regex finds keys before colon.
        result = result.replace(Regex("([{,])\\s*([a-zA-Z0-9_\\$]+)\\s*:"), "$1\"$2\":")
        // Remove trailing commas
        result = result.replace(Regex(",\\s*([}\\]])"), "$1")
        return result
    }
}
