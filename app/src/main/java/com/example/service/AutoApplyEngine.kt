package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AppPreferences
import com.example.data.ReplacementRule
import com.example.data.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real-time auto-apply engine. Traverses active accessibility tree, matches against
 * active rules, updates overlay patches as words scroll, and removes patches immediately
 * when words disappear.
 */
class AutoApplyEngine(
    private val service: AccessibilityService,
    private val repository: RuleRepository,
    private val preferences: AppPreferences,
    private val overlayContainerView: TextOverlayContainerView
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var scanJob: Job? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        isRunning = true
        preferences.setAutoApplyActive(true)
        scheduleScan(0L)
    }

    fun stop() {
        isRunning = false
        preferences.setAutoApplyActive(false)
        scanJob?.cancel()
        overlayContainerView.clearAll()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Immediate low-latency scan (20ms) so text follows smooth scrolling
                scheduleScan(20L)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleScan(preferences.debounceMs.value.coerceAtMost(50L))
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Immediate scan on window change or app switch (if word is gone, clears immediately)
                scheduleScan(0L)
            }
        }
    }

    fun triggerScan() {
        scheduleScan(0L)
    }

    private fun scheduleScan(delayMs: Long) {
        scanJob?.cancel()
        scanJob = scope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            performScan()
        }
    }

    private suspend fun performScan() {
        val activeRules = repository.getActiveRulesList()
        if (activeRules.isEmpty()) {
            withContext(Dispatchers.Main) {
                overlayContainerView.clearAll()
            }
            return
        }

        val rootNode = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }

        if (rootNode == null) {
            withContext(Dispatchers.Main) {
                overlayContainerView.clearAll()
            }
            return
        }

        val matchedPatches = mutableMapOf<String, ActiveTextPatch>()
        val tempRect = Rect()
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        try {
            traverseNode(rootNode, activeRules, matchedPatches, tempRect, screenWidth, screenHeight, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Apply newly matched patches. Any patch not in matchedPatches is removed automatically!
        withContext(Dispatchers.Main) {
            overlayContainerView.updatePatches(matchedPatches)
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        rules: List<ReplacementRule>,
        outPatches: MutableMap<String, ActiveTextPatch>,
        tempRect: Rect,
        screenWidth: Int,
        screenHeight: Int,
        depth: Int
    ) {
        if (node == null) return

        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()
            val effectiveText = if (!text.isNullOrBlank()) text else contentDesc

            if (!effectiveText.isNullOrBlank()) {
                node.getBoundsInScreen(tempRect)
                // Only consider nodes actually visible on screen
                if (tempRect.width() > 0 && tempRect.height() > 0 &&
                    tempRect.intersects(0, 0, screenWidth, screenHeight)
                ) {
                    val matchedRule = findMatchingRule(effectiveText, node, rules)
                    if (matchedRule != null) {
                        val key = "rule_${matchedRule.id}_${node.viewIdResourceName ?: effectiveText}_$depth"
                        outPatches[key] = ActiveTextPatch(
                            key = key,
                            bounds = Rect(tempRect),
                            replacementText = matchedRule.replacementText,
                            textColor = matchedRule.textColor,
                            backgroundColor = matchedRule.backgroundColor,
                            textSizeSp = matchedRule.textSizeSp,
                            isBold = matchedRule.isBold,
                            isItalic = matchedRule.isItalic
                        )
                    }
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                traverseNode(child, rules, outPatches, tempRect, screenWidth, screenHeight, depth + 1)
                try {
                    child.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun findMatchingRule(
        nodeText: String,
        node: AccessibilityNodeInfo,
        rules: List<ReplacementRule>
    ): ReplacementRule? {
        val nodePackage = node.packageName?.toString()
        val nodeViewId = node.viewIdResourceName

        // Priority 1: Exact text match
        for (rule in rules) {
            if (rule.matchMode == ReplacementRule.MATCH_EXACT) {
                if (rule.packageName != null && rule.packageName != nodePackage) continue
                if (rule.originalText.equals(nodeText, ignoreCase = true)) {
                    return rule
                }
            }
        }

        // Priority 2: View ID match
        if (nodeViewId != null) {
            for (rule in rules) {
                if (rule.matchMode == ReplacementRule.MATCH_VIEW_ID || rule.viewId != null) {
                    if (rule.viewId == nodeViewId) {
                        if (rule.packageName != null && rule.packageName != nodePackage) continue
                        return rule
                    }
                }
            }
        }

        // Priority 3: Contains text match
        for (rule in rules) {
            if (rule.matchMode == ReplacementRule.MATCH_CONTAINS) {
                if (rule.packageName != null && rule.packageName != nodePackage) continue
                if (nodeText.contains(rule.originalText, ignoreCase = true)) {
                    return rule
                }
            }
        }

        return null
    }
}
