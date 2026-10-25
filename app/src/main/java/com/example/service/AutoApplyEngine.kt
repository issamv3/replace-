package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance, zero-lag auto-apply engine.
 * Traverses active accessibility trees, indexes rules for O(1) instant matching,
 * captures all texts (small, large, nested), deduplicates only identical text collisions,
 * and maintains continuous real-time scroll tracking.
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
                // Ultra-low latency (15ms ~ 1 frame) so text moves with scrolling with zero lag
                scheduleScan(15L)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleScan(30L)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Instant scan on window or navigation events
                scheduleScan(0L)
            }
            else -> {
                scheduleScan(40L)
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

    private fun getScreenBounds(): Rect {
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
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

        // Fast O(1) lookup map for exact match rules
        val exactRulesMap = activeRules
            .filter { it.isEnabled && it.matchMode == ReplacementRule.MATCH_EXACT }
            .groupBy { it.originalText.trim().lowercase() }

        // Gather all root nodes: ALWAYS prioritize active foreground window AND scan all other windows
        val rootsToScan = mutableListOf<AccessibilityNodeInfo>()
        val seenRoots = mutableSetOf<AccessibilityNodeInfo>()

        try {
            val activeRoot = service.rootInActiveWindow
            if (activeRoot != null) {
                rootsToScan.add(activeRoot)
                seenRoots.add(activeRoot)
            }
        } catch (e: Exception) {
            // Fallback
        }

        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    val root = try { w.root } catch (e: Exception) { null }
                    if (root != null && !seenRoots.contains(root)) {
                        rootsToScan.add(root)
                        seenRoots.add(root)
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback if windows list is inaccessible
        }

        if (rootsToScan.isEmpty()) {
            withContext(Dispatchers.Main) {
                overlayContainerView.clearAll()
            }
            return
        }

        val rawCandidates = mutableListOf<ActiveTextPatch>()
        val tempRect = Rect()
        val screenBounds = getScreenBounds()

        for (root in rootsToScan) {
            try {
                traverseNode(root, activeRules, exactRulesMap, rawCandidates, tempRect, screenBounds)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    root.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        // Deduplicate overlapping candidates of the same text prioritizing true text views
        val deduplicatedPatches = deduplicatePatches(rawCandidates)

        withContext(Dispatchers.Main) {
            overlayContainerView.updatePatches(deduplicatedPatches)
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        rules: List<ReplacementRule>,
        exactRulesMap: Map<String, List<ReplacementRule>>,
        outCandidates: MutableList<ActiveTextPatch>,
        tempRect: Rect,
        screenBounds: Rect
    ) {
        if (node == null) return

        // Skip our own service overlay views
        val pkg = node.packageName?.toString()
        val cls = node.className?.toString() ?: ""
        if (pkg == service.packageName) {
            if (cls.contains("FloatingBubbleView") ||
                cls.contains("InspectionOverlayView") ||
                cls.contains("TextOverlayContainerView") ||
                cls.contains("HotspotTriggerView") ||
                node.text?.toString() == "⇄"
            ) {
                return
            }
        }

        // Read all possible text properties to ensure NO text is missed (small or large)
        val rawText = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else null
        val tooltipText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.tooltipText?.toString()?.trim()
        } else null

        val hasRealText = !rawText.isNullOrBlank()
        val effectiveText = when {
            hasRealText -> rawText
            !contentDesc.isNullOrBlank() -> contentDesc
            !hintText.isNullOrBlank() -> hintText
            !tooltipText.isNullOrBlank() -> tooltipText
            else -> null
        }

        if (!effectiveText.isNullOrBlank()) {
            node.getBoundsInScreen(tempRect)
            // Any text with valid bounds on screen is processed
            if (tempRect.width() > 0 && tempRect.height() > 0 &&
                Rect.intersects(tempRect, screenBounds)
            ) {
                val matchedRule = findMatchingRuleFast(effectiveText, node, rules, exactRulesMap)
                if (matchedRule != null) {
                    val finalReplacementText = if (matchedRule.matchMode == ReplacementRule.MATCH_CONTAINS &&
                        !effectiveText.trim().equals(matchedRule.originalText.trim(), ignoreCase = true)
                    ) {
                        try {
                            effectiveText.replace(
                                Regex("(?i)\\b" + Regex.escape(matchedRule.originalText) + "\\b"),
                                matchedRule.replacementText
                            ).ifEmpty {
                                effectiveText.replace(matchedRule.originalText, matchedRule.replacementText, ignoreCase = true)
                            }
                        } catch (e: Exception) {
                            effectiveText.replace(matchedRule.originalText, matchedRule.replacementText, ignoreCase = true)
                        }
                    } else {
                        matchedRule.replacementText
                    }

                    outCandidates.add(
                        ActiveTextPatch(
                            key = "rule_${matchedRule.id}_${tempRect.centerX()}_${tempRect.centerY()}",
                            bounds = Rect(tempRect),
                            replacementText = finalReplacementText,
                            textColor = matchedRule.textColor,
                            backgroundColor = matchedRule.backgroundColor,
                            textSizeSp = matchedRule.textSizeSp,
                            widthScale = matchedRule.widthScale,
                            isBold = matchedRule.isBold,
                            isItalic = matchedRule.isItalic,
                            hasRealText = hasRealText,
                            className = cls
                        )
                    )
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
                traverseNode(child, rules, exactRulesMap, outCandidates, tempRect, screenBounds)
                try {
                    child.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun getPatchQualityScore(patch: ActiveTextPatch): Int {
        var score = 0
        if (patch.hasRealText) score += 1000
        val cls = patch.className ?: ""
        if (cls.contains("TextView", ignoreCase = true)) score += 500
        if (cls.contains("Button", ignoreCase = true)) score += 300
        if (cls.contains("EditText", ignoreCase = true)) score += 300
        if (cls.contains("ImageView", ignoreCase = true) && !patch.hasRealText) score -= 800
        return score
    }

    /**
     * Deduplicates candidate patches.
     * Only deduplicates when two patches represent the SAME replacement text overlapping
     * (e.g. parent container and leaf text). Different texts are NEVER discarded!
     */
    private fun deduplicatePatches(candidates: List<ActiveTextPatch>): Map<String, ActiveTextPatch> {
        if (candidates.isEmpty()) return emptyMap()

        // Prioritize true TextViews over icon ImageViews with matching contentDescription
        val sorted = candidates.sortedWith(
            compareByDescending<ActiveTextPatch> { getPatchQualityScore(it) }
                .thenBy { it.bounds.width() * it.bounds.height() }
        )
        val accepted = mutableListOf<ActiveTextPatch>()

        for (candidate in sorted) {
            val hasOverlap = accepted.any { existing ->
                if (!Rect.intersects(candidate.bounds, existing.bounds)) {
                    false
                } else {
                    // Only deduplicate if they share the same replacement text
                    val isSameReplacement = candidate.replacementText.equals(existing.replacementText, ignoreCase = true)
                    if (!isSameReplacement) {
                        false // Different texts must never cancel each other out!
                    } else {
                        val intersectLeft = max(candidate.bounds.left, existing.bounds.left)
                        val intersectTop = max(candidate.bounds.top, existing.bounds.top)
                        val intersectRight = min(candidate.bounds.right, existing.bounds.right)
                        val intersectBottom = min(candidate.bounds.bottom, existing.bounds.bottom)
                        val intersectArea = (intersectRight - intersectLeft).coerceAtLeast(0) *
                                (intersectBottom - intersectTop).coerceAtLeast(0)

                        val candidateArea = candidate.bounds.width() * candidate.bounds.height()
                        val existingArea = existing.bounds.width() * existing.bounds.height()
                        val minArea = min(candidateArea, existingArea).coerceAtLeast(1)

                        (intersectArea.toFloat() / minArea) > 0.40f ||
                                (abs(candidate.bounds.centerX() - existing.bounds.centerX()) < 24 &&
                                 abs(candidate.bounds.centerY() - existing.bounds.centerY()) < 24)
                    }
                }
            }

            if (!hasOverlap) {
                accepted.add(candidate)
            }
        }

        return accepted.associateBy { it.key }
    }

    private fun findMatchingRuleFast(
        nodeText: String,
        node: AccessibilityNodeInfo,
        rules: List<ReplacementRule>,
        exactRulesMap: Map<String, List<ReplacementRule>>
    ): ReplacementRule? {
        val cleanNodeText = nodeText.trim()
        if (cleanNodeText.isEmpty()) return null

        val nodePackage = node.packageName?.toString()
        val nodeViewId = node.viewIdResourceName
        val lowerText = cleanNodeText.lowercase()

        // Priority 1: Instant O(1) exact text match
        val exactCandidates = exactRulesMap[lowerText]
        if (exactCandidates != null) {
            for (rule in exactCandidates) {
                if (rule.packageName.isNullOrBlank() || rule.packageName == nodePackage) {
                    return rule
                }
            }
        }

        // Priority 2: Contains text match (only if explicitly configured with length >= 2)
        for (rule in rules) {
            if (!rule.isEnabled) continue
            if (rule.matchMode == ReplacementRule.MATCH_CONTAINS) {
                val cleanOriginal = rule.originalText.trim()
                if (cleanOriginal.length >= 2) {
                    if (rule.packageName.isNullOrBlank() || rule.packageName == nodePackage) {
                        if (cleanNodeText.contains(cleanOriginal, ignoreCase = true)) {
                            return rule
                        }
                    }
                }
            }
        }

        // Priority 3: View ID match (only if explicitly set to MATCH_VIEW_ID by user)
        if (!nodeViewId.isNullOrBlank()) {
            for (rule in rules) {
                if (!rule.isEnabled) continue
                if (rule.matchMode == ReplacementRule.MATCH_VIEW_ID && !rule.viewId.isNullOrBlank()) {
                    if (rule.packageName.isNullOrBlank() || rule.packageName == nodePackage) {
                        if (rule.viewId == nodeViewId) {
                            val cleanOriginal = rule.originalText.trim()
                            if (cleanOriginal.isEmpty() || cleanNodeText.equals(cleanOriginal, ignoreCase = true)) {
                                return rule
                            }
                        }
                    }
                }
            }
        }

        return null
    }
}
