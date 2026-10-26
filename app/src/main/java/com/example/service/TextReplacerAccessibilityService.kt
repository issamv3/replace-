package com.example.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AppPreferences
import com.example.data.ReplacementRule
import com.example.data.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextReplacerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var windowManager: WindowManager
    private lateinit var preferences: AppPreferences
    private lateinit var repository: RuleRepository

    private var floatingBubbleView: FloatingBubbleView? = null
    private var overlayContainerView: TextOverlayContainerView? = null
    private var inspectionOverlayView: InspectionOverlayView? = null
    private var hotspotTriggerView: HotspotTriggerView? = null
    private var autoApplyEngine: AutoApplyEngine? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val database = AppDatabase.getInstance(this)
        preferences = AppPreferences.getInstance(this)
        repository = RuleRepository(database.replacementRuleDao(), preferences)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true

        startForegroundNotification()
        setupOverlayViews()

        autoApplyEngine = AutoApplyEngine(this, repository, preferences, overlayContainerView!!)
        autoApplyEngine?.start()
        floatingBubbleView?.updatePlayState(true)

        // Observe bubble visibility
        serviceScope.launch {
            preferences.isBubbleHidden.collect { hidden ->
                withContext(Dispatchers.Main) {
                    if (hidden) {
                        floatingBubbleView?.hideBubble()
                        hotspotTriggerView?.hide()
                    } else {
                        hotspotTriggerView?.hide()
                        floatingBubbleView?.showBubble()
                    }
                }
            }
        }

        serviceScope.launch {
            preferences.hotspotPosition.collect { pos ->
                withContext(Dispatchers.Main) {
                    hotspotTriggerView?.applyPosition(pos)
                }
            }
        }
    }

    private fun setupOverlayViews() {
        // 1. Text Overlay Container (renders live text replacement patches)
        if (overlayContainerView == null) {
            val container = TextOverlayContainerView(this, windowManager)
            try {
                windowManager.addView(container, container.layoutParamsWindow)
                overlayContainerView = container
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Floating Bubble Icon (draggable menu)
        if (floatingBubbleView == null) {
            val bubble = FloatingBubbleView(
                context = this,
                windowManager = windowManager,
                preferences = preferences,
                onAddReplacementClicked = { onAddReplacementTriggered() },
                onToggleAutoApplyClicked = { onToggleAutoApplyTriggered() },
                onBubbleDismissed = {
                    preferences.setBubbleHidden(true)
                    floatingBubbleView?.hideBubble()
                    hotspotTriggerView?.hide()
                }
            )
            try {
                windowManager.addView(bubble, bubble.layoutParamsWindow)
                floatingBubbleView = bubble
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Hotspot Trigger View (shown only if explicitly configured)
        if (hotspotTriggerView == null) {
            val hotspot = HotspotTriggerView(
                context = this,
                windowManager = windowManager,
                onHotspotTapped = {
                    restoreBubbleFromExternal()
                }
            )
            hotspot.applyPosition(preferences.hotspotPosition.value)
            hotspotTriggerView = hotspot
        }

        if (preferences.isBubbleHidden.value) {
            floatingBubbleView?.hideBubble()
            hotspotTriggerView?.hide()
        } else {
            hotspotTriggerView?.hide()
            floatingBubbleView?.showBubble()
        }
    }

    fun restoreBubbleFromExternal() {
        preferences.setBubbleHidden(false)
        hotspotTriggerView?.hide()
        floatingBubbleView?.showBubble()
    }

    fun hideBubbleFromExternal() {
        preferences.setBubbleHidden(true)
        floatingBubbleView?.hideBubble()
        hotspotTriggerView?.show()
    }

    private fun onToggleAutoApplyTriggered() {
        val engine = autoApplyEngine ?: return
        if (engine.isRunning) {
            engine.stop()
            floatingBubbleView?.updatePlayState(false)
        } else {
            engine.start()
            floatingBubbleView?.updatePlayState(true)
        }
    }

    private fun onAddReplacementTriggered() {
        // Collapse bubble menu and temporarily hide bubble so it does not obstruct screen text
        floatingBubbleView?.collapseMenu()
        floatingBubbleView?.hideBubble()

        // Gather inspectable elements across active window and all interactive system windows
        val rootsToScan = mutableListOf<AccessibilityNodeInfo>()
        val seenRoots = mutableSetOf<AccessibilityNodeInfo>()

        // 1. Prioritize root in active foreground window
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                rootsToScan.add(activeRoot)
                seenRoots.add(activeRoot)
            }
        } catch (e: Exception) {
            // Ignore
        }

        // 2. Also scan all windows (status bar, navigation, multi-window, dialogs, popups)
        try {
            val wins = windows
            if (!wins.isNullOrEmpty()) {
                for (w in wins) {
                    val r = try { w.root } catch (e: Exception) { null }
                    if (r != null && !seenRoots.contains(r)) {
                        rootsToScan.add(r)
                        seenRoots.add(r)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        val rawItems = mutableListOf<InspectableItem>()
        val tempRect = Rect()

        for (root in rootsToScan) {
            collectInspectableNodes(root, rawItems, tempRect)
            try {
                root.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Deduplicate overlapping items prioritizing real text views with exact bounds
        val items = deduplicateInspectableItems(rawItems)

        // Capture screenshot to sample exact text & background colors
        captureScreenAndLaunchInspection(items)
    }

    private fun getNodeQualityScore(item: InspectableItem): Int {
        var score = 0
        if (item.hasRealText) score += 1000
        val cls = item.className ?: ""
        if (cls.contains("TextView", ignoreCase = true)) score += 500
        if (cls.contains("Button", ignoreCase = true)) score += 300
        if (cls.contains("EditText", ignoreCase = true)) score += 300
        if (cls.contains("Switch", ignoreCase = true)) score += 200
        if (item.isLeaf) score += 200
        if (cls.contains("ImageView", ignoreCase = true) && !item.hasRealText) score -= 800
        return score
    }

    private fun deduplicateInspectableItems(items: List<InspectableItem>): MutableList<InspectableItem> {
        if (items.isEmpty()) return mutableListOf()

        // Sort items so higher-quality representations (real TextViews with text) are processed first
        val sorted = items.sortedWith(
            compareByDescending<InspectableItem> { getNodeQualityScore(it) }
                .thenBy { it.bounds.width() * it.bounds.height() }
        )

        val accepted = mutableListOf<InspectableItem>()
        for (item in sorted) {
            val isDuplicate = accepted.any { existing ->
                if (!Rect.intersects(item.bounds, existing.bounds)) {
                    false
                } else {
                    // ONLY deduplicate if they represent the EXACT SAME text
                    val isExactSameText = item.text.trim().equals(existing.text.trim(), ignoreCase = true)
                    if (!isExactSameText) {
                        false // Different texts on screen must NEVER hide each other!
                    } else {
                        val intersectLeft = Math.max(item.bounds.left, existing.bounds.left)
                        val intersectTop = Math.max(item.bounds.top, existing.bounds.top)
                        val intersectRight = Math.min(item.bounds.right, existing.bounds.right)
                        val intersectBottom = Math.min(item.bounds.bottom, existing.bounds.bottom)
                        val intersectArea = (intersectRight - intersectLeft).coerceAtLeast(0) *
                                (intersectBottom - intersectTop).coerceAtLeast(0)

                        val itemArea = (item.bounds.width() * item.bounds.height()).coerceAtLeast(1)
                        val existingArea = (existing.bounds.width() * existing.bounds.height()).coerceAtLeast(1)
                        val minArea = Math.min(itemArea, existingArea)

                        (intersectArea.toFloat() / minArea) > 0.40f ||
                                (Math.abs(item.bounds.centerX() - existing.bounds.centerX()) < 24 &&
                                 Math.abs(item.bounds.centerY() - existing.bounds.centerY()) < 24)
                    }
                }
            }
            if (!isDuplicate) {
                accepted.add(item)
            }
        }
        return accepted
    }

    private fun collectInspectableNodes(
        node: AccessibilityNodeInfo?,
        outItems: MutableList<InspectableItem>,
        tempRect: Rect
    ) {
        if (node == null) return

        // Skip our own overlay views so they don't produce false detection boxes
        val pkg = node.packageName?.toString()
        val cls = node.className?.toString() ?: ""
        if (pkg == packageName) {
            if (cls.contains("FloatingBubbleView") ||
                cls.contains("InspectionOverlayView") ||
                cls.contains("TextOverlayContainerView") ||
                cls.contains("HotspotTriggerView") ||
                node.text?.toString() == "⇄" ||
                node.text?.toString()?.contains("Tap any highlighted text") == true
            ) {
                return
            }
        }

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

        val isLeaf = node.childCount == 0

        if (!effectiveText.isNullOrBlank()) {
            node.getBoundsInScreen(tempRect)
            if (tempRect.width() > 0 && tempRect.height() > 0) {
                val density = resources.displayMetrics.density
                val calculatedSizeSp = (tempRect.height() * 0.70f) / density

                val textSpanned = node.text
                var isBoldNode = false
                if (textSpanned is android.text.Spanned) {
                    val styleSpans = textSpanned.getSpans(0, textSpanned.length, android.text.style.StyleSpan::class.java)
                    if (styleSpans.any { it.style == android.graphics.Typeface.BOLD || it.style == android.graphics.Typeface.BOLD_ITALIC }) {
                        isBoldNode = true
                    }
                }

                // Specifically detect dates/timestamps like 2026-10-23 09:45
                val dateMatches = DateDetector.findDates(effectiveText, tempRect, density)
                for (dateMatch in dateMatches) {
                    outItems.add(
                        InspectableItem(
                            text = dateMatch.dateText,
                            bounds = dateMatch.bounds,
                            packageName = node.packageName?.toString(),
                            viewId = node.viewIdResourceName,
                            className = node.className?.toString(),
                            textSizeSp = calculatedSizeSp,
                            hasRealText = hasRealText,
                            isLeaf = true,
                            isBold = isBoldNode,
                            fontWeight = if (isBoldNode) 700 else 700,
                            fullSentence = effectiveText
                        )
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                collectInspectableNodes(child, outItems, tempRect)
                try {
                    child.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun captureScreenAndLaunchInspection(items: MutableList<InspectableItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()

                        processItemsAndShowInspection(items, bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        // Secure window or failure -> proceed with null bitmap (uses contrast fallback colors)
                        processItemsAndShowInspection(items, null)
                    }
                }
            )
        } else {
            // Older API fallback
            processItemsAndShowInspection(items, null)
        }
    }

    private fun processItemsAndShowInspection(items: MutableList<InspectableItem>, bitmap: Bitmap?) {
        serviceScope.launch {
            // If OCR fallback is enabled and we have a bitmap, supplement any texts missed by accessibility tree
            if (preferences.ocrFallbackEnabled.value && bitmap != null) {
                val density = resources.displayMetrics.density
                val ocrBlocks = OcrFallbackHelper.recognizeText(bitmap)
                for (block in ocrBlocks) {
                    val dateMatches = DateDetector.findDates(block.text, block.bounds, density)
                    for (d in dateMatches) {
                        if (items.none { it.bounds.contains(d.bounds.centerX(), d.bounds.centerY()) }) {
                            items.add(
                                InspectableItem(
                                    text = d.dateText,
                                    bounds = d.bounds,
                                    packageName = null,
                                    hasRealText = true,
                                    isLeaf = true,
                                    fullSentence = block.text
                                )
                            )
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                launchInspectionOverlay(items, bitmap)
            }
        }
    }

    private fun launchInspectionOverlay(items: List<InspectableItem>, bitmap: Bitmap?) {
        removeInspectionOverlay()

        val overlay = InspectionOverlayView(
            context = this,
            windowManager = windowManager,
            inspectableItems = items,
            screenshotBitmap = bitmap,
            onDismiss = {
                inspectionOverlayView = null
                // Restore floating bubble if not hidden by user
                if (!preferences.isBubbleHidden.value) {
                    floatingBubbleView?.showBubble()
                }
            },
            onRuleConfirmed = { rule ->
                applyAndSaveRule(rule)
            }
        )

        try {
            windowManager.addView(overlay, overlay.layoutParamsWindow)
            inspectionOverlayView = overlay
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyAndSaveRule(rule: ReplacementRule) {
        serviceScope.launch {
            // 1. Save rule to Room database
            repository.insertRule(rule)

            // 2. Trigger autoApplyEngine to scan live tree and track node dynamically
            if (autoApplyEngine?.isRunning != true) {
                autoApplyEngine?.start()
                floatingBubbleView?.updatePlayState(true)
            } else {
                autoApplyEngine?.triggerScan()
            }
        }
    }

    private fun removeInspectionOverlay() {
        inspectionOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            inspectionOverlayView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            autoApplyEngine?.onAccessibilityEvent(event)
        }
    }

    override fun onInterrupt() {
        // Accessibility service interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        autoApplyEngine?.stop()

        removeInspectionOverlay()

        floatingBubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            floatingBubbleView = null
        }

        overlayContainerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            overlayContainerView = null
        }

        if (instance == this) {
            instance = null
        }

        hotspotTriggerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            hotspotTriggerView = null
        }
    }

    private fun startForegroundNotification() {
        val channelId = "text_shift_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive = _isServiceActive.asStateFlow()

        @Volatile
        private var instance: TextReplacerAccessibilityService? = null

        fun restoreBubble() {
            instance?.restoreBubbleFromExternal()
        }

        fun hideBubble() {
            instance?.hideBubbleFromExternal()
        }
    }
}
