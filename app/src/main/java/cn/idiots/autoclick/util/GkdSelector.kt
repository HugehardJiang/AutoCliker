package cn.idiots.autoclick.util

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.util.Log

/**
 * A robust, non-recursive GKD-style selector engine.
 * Specifically optimized to prevent StackOverflowError on deep UI hierarchies.
 */
class GkdSelector(private val selector: String) {

    private data class Condition(
        val key: String,
        val operator: String,
        val value: String
    )

    private interface ConditionNode {
        fun eval(node: AccessibilityNodeInfo): Boolean
    }

    private inner class CondLeaf(val cond: Condition) : ConditionNode {
        override fun eval(node: AccessibilityNodeInfo): Boolean = evalCondition(node, cond)
    }

    private inner class CondAnd(val left: ConditionNode, val right: ConditionNode) : ConditionNode {
        override fun eval(node: AccessibilityNodeInfo): Boolean = left.eval(node) && right.eval(node)
    }

    private inner class CondOr(val left: ConditionNode, val right: ConditionNode) : ConditionNode {
        override fun eval(node: AccessibilityNodeInfo): Boolean = left.eval(node) || right.eval(node)
    }

    private data class SelectorUnit(
        val isTarget: Boolean,
        val className: String?,
        val conditionNode: ConditionNode?
    )

    private val units: List<SelectorUnit>
    private val relationships: List<String>
    private var targetIndex: Int = -1

    init {
        val parsedUnits = mutableListOf<SelectorUnit>()
        val parsedRelationships = mutableListOf<String>()
        
        val unitRegex = Regex("(@)?([\\w.*]*)((?:\\[.*?\\])+)")
        val matches = unitRegex.findAll(selector).toList()
        
        for (i in matches.indices) {
            val match = matches[i]
            val isTarget = match.groupValues[1] == "@"
            if (isTarget) targetIndex = i
            
            val className = match.groupValues[2].let { if (it.isEmpty() || it == "*") null else it }
            val attributesStr = match.groupValues[3]
            
            val bracketRegex = Regex("\\[(.*?)\\]")
            val attrRegex = Regex("([\\w.]+)\\s*([=^$*!<>]=?|~=)\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s|&]+))")
            
            val bracketNodes = mutableListOf<ConditionNode>()
            bracketRegex.findAll(attributesStr).forEach { bracketMatch ->
                val innerStr = bracketMatch.groupValues[1]
                val conditionMatches = attrRegex.findAll(innerStr).toList()
                if (conditionMatches.isEmpty()) return@forEach
                
                val conditions = mutableListOf<Condition>()
                val ops = mutableListOf<String>()
                
                var lastEnd = 0
                for (j in conditionMatches.indices) {
                    val condMatch = conditionMatches[j]
                    if (j > 0) {
                        val between = innerStr.substring(lastEnd, condMatch.range.first)
                        if (between.contains("||")) ops.add("||")
                        else ops.add("&&")
                    }
                    val key = condMatch.groupValues[1]
                    val op = condMatch.groupValues[2]
                    val val3 = condMatch.groups[3]?.value
                    val val4 = condMatch.groups[4]?.value
                    val val5 = condMatch.groups[5]?.value
                    val finalValue = val3 ?: val4 ?: val5 ?: ""
                    
                    conditions.add(Condition(key, op, finalValue))
                    lastEnd = condMatch.range.last + 1
                }
                
                val orGroups = mutableListOf<ConditionNode>()
                var currentAndGroup = mutableListOf<ConditionNode>()
                
                for (j in conditions.indices) {
                    currentAndGroup.add(CondLeaf(conditions[j]))
                    if (j < ops.size) {
                        if (ops[j] == "||") {
                            orGroups.add(buildAndTree(currentAndGroup))
                            currentAndGroup = mutableListOf()
                        }
                    }
                }
                if (currentAndGroup.isNotEmpty()) {
                    orGroups.add(buildAndTree(currentAndGroup))
                }
                
                bracketNodes.add(buildOrTree(orGroups))
            }
            
            val unitNode = if (bracketNodes.isEmpty()) null else buildAndTree(bracketNodes)
            parsedUnits.add(SelectorUnit(isTarget, className, unitNode))
            
            if (i < matches.size - 1) {
                val endOfCurrent = match.range.last + 1
                val startOfNext = matches[i+1].range.first
                val between = selector.substring(endOfCurrent, startOfNext).trim()
                val rel = when {
                    between.startsWith(">>>") -> ">>>"
                    between.startsWith(">>") -> ">>"
                    between.startsWith(">") -> ">"
                    between.startsWith("<<<") -> "<<<"
                    between.startsWith("<<") -> "<<"
                    between.startsWith("+") -> "+"
                    between.startsWith("-") -> "-"
                    between.startsWith("~") -> "~"
                    else -> " "
                }
                parsedRelationships.add(rel)
            }
        }
        units = parsedUnits
        relationships = parsedRelationships
        if (targetIndex == -1 && units.isNotEmpty()) {
            targetIndex = units.size - 1
        }
    }

    private fun buildAndTree(nodes: List<ConditionNode>): ConditionNode {
        require(nodes.isNotEmpty())
        var root = nodes[0]
        for (i in 1 until nodes.size) {
            root = CondAnd(root, nodes[i])
        }
        return root
    }

    private fun buildOrTree(nodes: List<ConditionNode>): ConditionNode {
        require(nodes.isNotEmpty())
        var root = nodes[0]
        for (i in 1 until nodes.size) {
            root = CondOr(root, nodes[i])
        }
        return root
    }

    fun find(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (units.isEmpty()) return null
        
        // Iterative DFS to find the first unit match
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(root)
        
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            
            val matchedNodes = arrayOfNulls<AccessibilityNodeInfo>(units.size)
            if (matchChain(node, 0, matchedNodes)) {
                return matchedNodes[targetIndex]
            }
            
            // Push children to stack for iterative DFS
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        
        return null
    }

    /**
     * Matches a chain starting from a specific node.
     * unitIndex is small (usually < 5), so recursion here is safe.
     */
    private fun matchChain(node: AccessibilityNodeInfo, unitIndex: Int, matchedNodes: Array<AccessibilityNodeInfo?>): Boolean {
        val unit = units[unitIndex]
        if (!matchesUnit(node, unit)) return false
        
        matchedNodes[unitIndex] = node
        if (unitIndex == units.size - 1) return true

        val rel = relationships[unitIndex]
        return when (rel) {
            ">" -> {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (matchChain(child, unitIndex + 1, matchedNodes)) return true
                }
                false
            }
            " " , ">>", ">>>" -> {
                matchInDescendantsIterative(node, unitIndex + 1, matchedNodes)
            }
            "<<" -> {
                val parent = node.parent
                if (parent != null && matchChain(parent, unitIndex + 1, matchedNodes)) true else false
            }
            "<<<" -> {
                var current = node.parent
                while (current != null) {
                    if (matchChain(current, unitIndex + 1, matchedNodes)) return true
                    current = current.parent
                }
                false
            }
            "+" -> {
                val next = getSibling(node, 1)
                if (next != null) matchChain(next, unitIndex + 1, matchedNodes) else false
            }
            "-" -> {
                val prev = getSibling(node, -1)
                if (prev != null) matchChain(prev, unitIndex + 1, matchedNodes) else false
            }
            "~" -> {
                val parent = node.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChild(i) ?: continue
                        if (sibling == node) continue
                        if (matchChain(sibling, unitIndex + 1, matchedNodes)) return true
                    }
                }
                false
            }
            else -> false
        }
    }

    /**
     * Iterative search for descendants to avoid StackOverflow.
     */
    private fun matchInDescendantsIterative(startNode: AccessibilityNodeInfo, unitIndex: Int, matchedNodes: Array<AccessibilityNodeInfo?>): Boolean {
        val stack = mutableListOf<AccessibilityNodeInfo>()
        for (i in startNode.childCount - 1 downTo 0) {
            startNode.getChild(i)?.let { stack.add(it) }
        }
        
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            if (matchChain(current, unitIndex, matchedNodes)) return true
            
            for (i in current.childCount - 1 downTo 0) {
                current.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    private fun getSibling(node: AccessibilityNodeInfo, offset: Int): AccessibilityNodeInfo? {
        val parent = node.parent ?: return null
        for (i in 0 until parent.childCount) {
            // Note: AccessibilityNodeInfo objects might be different instances for same node
            // But we can check bounds and ID or class as a proxy, or use equals()
            if (parent.getChild(i) == node) {
                val targetIndex = i + offset
                if (targetIndex >= 0 && targetIndex < parent.childCount) {
                    return parent.getChild(targetIndex)
                }
                break
            }
        }
        return null
    }

    private fun matchesUnit(node: AccessibilityNodeInfo, unit: SelectorUnit): Boolean {
        if (unit.className != null && !node.className.isNullOrBlank() && !node.className.contains(unit.className, ignoreCase = true)) {
            return false
        }
        return unit.conditionNode?.eval(node) ?: true
    }

    private fun evalCondition(node: AccessibilityNodeInfo, cond: Condition): Boolean {
        val actualValue = getPropertyValue(node, cond.key) ?: return false
        val targetValue = cond.value

        return when (cond.operator) {
            "=" -> actualValue == targetValue
            "*=" -> actualValue.contains(targetValue, ignoreCase = true)
            "^=" -> actualValue.startsWith(targetValue, ignoreCase = true)
            "$=" -> actualValue.endsWith(targetValue, ignoreCase = true)
            "!=" -> actualValue != targetValue
            "<" -> compareNumeric(actualValue, targetValue) < 0
            ">" -> compareNumeric(actualValue, targetValue) > 0
            "<=" -> compareNumeric(actualValue, targetValue) <= 0
            ">=" -> compareNumeric(actualValue, targetValue) >= 0
            else -> actualValue == targetValue
        }
    }

    private fun getPropertyValue(node: AccessibilityNodeInfo, key: String): String? {
        return when (key) {
            "id", "vid" -> {
                val fullId = node.viewIdResourceName
                if (key == "vid") fullId?.substringAfterLast("/") else fullId
            }
            "text" -> node.text?.toString()
            "text.length" -> node.text?.length?.toString()
            "desc" -> node.contentDescription?.toString()
            "desc.length" -> node.contentDescription?.length?.toString()
            "name" -> node.className?.toString()
            "clickable" -> node.isClickable.toString()
            "visibleToUser" -> node.isVisibleToUser.toString()
            "childCount" -> node.childCount.toString()
            "enabled" -> node.isEnabled.toString()
            "bounds" -> {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
            }
            else -> null
        }
    }

    private fun compareNumeric(actual: String, target: String): Int {
        val a = actual.toDoubleOrNull() ?: 0.0
        val t = target.toDoubleOrNull() ?: 0.0
        return a.compareTo(t)
    }
}
