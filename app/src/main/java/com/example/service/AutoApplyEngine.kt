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
 * Real-time auto-apply engine. Traverses active accessibility windows, matches against
 * active rules, deduplicates overlapping/ancestor node hits to eliminate repeated texts,
 * and updates overlay patches so they follow words and disappear when words are gone.
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
                // Low-latency scan (20ms) so text follows smooth scrolling
                scheduleScan(20L)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleScan(preferences.debounceMs.value.coerceAtMost(40L))
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Immediate scan on window change, app switch, dialogs, or clicks
                scheduleScan(0L)
            }
            else -> {
                scheduleScan(preferences.debounceMs.value.coerceAtMost(60L))
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

        // Collect all root nodes across all active windows to support all screens & split-views
        val rootsToScan = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    val root = w.root
                    if (root != null) {
                        rootsToScan.add(root)
                    }
                }
            }
        } catch (e: Exception) {
            // Some environments restrict windows access, fallback to rootInActiveWindow
        }

        if (rootsToScan.isEmpty()) {
            val fallbackRoot = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            if (fallbackRoot != null) {
                rootsToScan.add(fallbackRoot)
            }
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
                traverseNode(root, activeRules, rawCandidates, tempRect, screenBounds)
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

        // Deduplicate overlapping candidates to eliminate 2x/3x repeated text!
        val deduplicatedPatches = deduplicatePatches(rawCandidates)

        // Apply newly matched patches. Any patch not in deduplicatedPatches is removed automatically!
        withContext(Dispatchers.Main) {
            overlayContainerView.updatePatches(deduplicatedPatches)
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        rules: List<ReplacementRule>,
        outCandidates: MutableList<ActiveTextPatch>,
        tempRect: Rect,
        screenBounds: Rect
    ) {
        if (node == null) return

        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()
            val effectiveText = if (!text.isNullOrBlank()) text else contentDesc

            if (!effectiveText.isNullOrBlank()) {
                node.getBoundsInScreen(tempRect)
                // Ensure the node is actually visible within the screen bounds
                if (tempRect.width() > 0 && tempRect.height() > 0 &&
                    Rect.intersects(tempRect, screenBounds)
                ) {
                    val matchedRule = findMatchingRule(effectiveText, node, rules)
                    if (matchedRule != null) {
                        outCandidates.add(
                            ActiveTextPatch(
                                key = "rule_${matchedRule.id}_${tempRect.centerX()}_${tempRect.centerY()}",
                                bounds = Rect(tempRect),
                                replacementText = matchedRule.replacementText,
                                textColor = matchedRule.textColor,
                                backgroundColor = matchedRule.backgroundColor,
                                textSizeSp = matchedRule.textSizeSp,
                                isBold = matchedRule.isBold,
                                isItalic = matchedRule.isItalic
                            )
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
                traverseNode(child, rules, outCandidates, tempRect, screenBounds)
                try {
                    child.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Deduplicates candidate patches.
     * Container layouts in Compose and Android often duplicate text from child leaf views.
     * By sorting by bounding box area ascending, the smallest, most specific leaf text view
     * is accepted first, and any parent/overlapping duplicate is rejected.
     */
    private fun deduplicatePatches(candidates: List<ActiveTextPatch>): Map<String, ActiveTextPatch> {
        if (candidates.isEmpty()) return emptyMap()

        // Sort by area ascending: smallest (most specific leaf view) first
        val sorted = candidates.sortedBy { it.bounds.width() * it.bounds.height() }
        val accepted = mutableListOf<ActiveTextPatch>()

        for (candidate in sorted) {
            val hasOverlap = accepted.any { existing ->
                if (!Rect.intersects(candidate.bounds, existing.bounds)) {
                    false
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

                    // If overlap is more than 30% or centers are nearly identical, it is the same visual element
                    (intersectArea.toFloat() / minArea) > 0.30f ||
                            (abs(candidate.bounds.centerX() - existing.bounds.centerX()) < 24 &&
                             abs(candidate.bounds.centerY() - existing.bounds.centerY()) < 24)
                }
            }

            if (!hasOverlap) {
                accepted.add(candidate)
            }
        }

        return accepted.associateBy { it.key }
    }

    private fun findMatchingRule(
        nodeText: String,
        node: AccessibilityNodeInfo,
        rules: List<ReplacementRule>
    ): ReplacementRule? {
        val cleanNodeText = nodeText.trim()
        if (cleanNodeText.isEmpty()) return null

        val nodePackage = node.packageName?.toString()
        val nodeViewId = node.viewIdResourceName

        // Priority 1: Exact text match (Strict: the node text MUST match the original text)
        for (rule in rules) {
            if (!rule.isEnabled) continue
            val cleanOriginal = rule.originalText.trim()
            if (cleanOriginal.isEmpty()) continue

            // If rule is scoped to a specific package, enforce it
            if (!rule.packageName.isNullOrBlank() && rule.packageName != nodePackage) {
                continue
            }

            if (rule.matchMode == ReplacementRule.MATCH_EXACT) {
                if (cleanNodeText.equals(cleanOriginal, ignoreCase = true)) {
                    return rule
                }
            }
        }

        // Priority 2: Contains text match (Only if explicitly set to MATCH_CONTAINS and >= 2 chars)
        for (rule in rules) {
            if (!rule.isEnabled) continue
            val cleanOriginal = rule.originalText.trim()
            if (cleanOriginal.length < 2) continue

            if (!rule.packageName.isNullOrBlank() && rule.packageName != nodePackage) {
                continue
            }

            if (rule.matchMode == ReplacementRule.MATCH_CONTAINS) {
                if (cleanNodeText.contains(cleanOriginal, ignoreCase = true)) {
                    return rule
                }
            }
        }

        // Priority 3: View ID match ONLY if matchMode was explicitly set to MATCH_VIEW_ID
        if (!nodeViewId.isNullOrBlank()) {
            for (rule in rules) {
                if (!rule.isEnabled) continue
                if (rule.matchMode == ReplacementRule.MATCH_VIEW_ID && !rule.viewId.isNullOrBlank()) {
                    if (!rule.packageName.isNullOrBlank() && rule.packageName != nodePackage) {
                        continue
                    }
                    if (rule.viewId == nodeViewId) {
                        val cleanOriginal = rule.originalText.trim()
                        if (cleanOriginal.isEmpty() || cleanNodeText.equals(cleanOriginal, ignoreCase = true)) {
                            return rule
                        }
                    }
                }
            }
        }

        return null
    }
}
