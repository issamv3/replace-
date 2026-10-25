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
    private var autoApplyEngine: AutoApplyEngine? = null

    override fun onCreate() {
        super.onCreate()
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
                onToggleAutoApplyClicked = { onToggleAutoApplyTriggered() }
            )
            try {
                windowManager.addView(bubble, bubble.layoutParamsWindow)
                floatingBubbleView = bubble
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
        // Collapse bubble menu
        floatingBubbleView?.collapseMenu()

        // Gather inspectable elements across all active windows (supports multi-window & dialogs)
        val rootsToScan = mutableListOf<AccessibilityNodeInfo>()
        try {
            val wins = windows
            if (!wins.isNullOrEmpty()) {
                for (w in wins) {
                    val r = w.root
                    if (r != null) rootsToScan.add(r)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        if (rootsToScan.isEmpty()) {
            val r = try { rootInActiveWindow } catch (e: Exception) { null }
            if (r != null) rootsToScan.add(r)
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

        // Deduplicate overlapping items so only the clean leaf text elements are highlighted
        val items = deduplicateInspectableItems(rawItems)

        // Capture screenshot to sample exact text & background colors
        captureScreenAndLaunchInspection(items)
    }

    private fun deduplicateInspectableItems(items: List<InspectableItem>): MutableList<InspectableItem> {
        if (items.isEmpty()) return mutableListOf()
        val sorted = items.sortedBy { it.bounds.width() * it.bounds.height() }
        val accepted = mutableListOf<InspectableItem>()
        for (item in sorted) {
            val overlaps = accepted.any { existing ->
                if (!Rect.intersects(item.bounds, existing.bounds)) {
                    false
                } else {
                    val intersectLeft = Math.max(item.bounds.left, existing.bounds.left)
                    val intersectTop = Math.max(item.bounds.top, existing.bounds.top)
                    val intersectRight = Math.min(item.bounds.right, existing.bounds.right)
                    val intersectBottom = Math.min(item.bounds.bottom, existing.bounds.bottom)
                    val intersectArea = (intersectRight - intersectLeft).coerceAtLeast(0) *
                            (intersectBottom - intersectTop).coerceAtLeast(0)

                    val itemArea = item.bounds.width() * item.bounds.height()
                    val existingArea = existing.bounds.width() * existing.bounds.height()
                    val minArea = Math.min(itemArea, existingArea).coerceAtLeast(1)

                    (intersectArea.toFloat() / minArea) > 0.30f ||
                            (Math.abs(item.bounds.centerX() - existing.bounds.centerX()) < 24 &&
                             Math.abs(item.bounds.centerY() - existing.bounds.centerY()) < 24)
                }
            }
            if (!overlaps) {
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

        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()
            val effectiveText = if (!text.isNullOrBlank()) text else contentDesc

            if (!effectiveText.isNullOrBlank()) {
                node.getBoundsInScreen(tempRect)
                if (tempRect.width() > 0 && tempRect.height() > 0) {
                    val density = resources.displayMetrics.density
                    val calculatedSizeSp = (tempRect.height() * 0.70f) / density
                    outItems.add(
                        InspectableItem(
                            text = effectiveText,
                            bounds = Rect(tempRect),
                            packageName = node.packageName?.toString(),
                            viewId = node.viewIdResourceName,
                            className = node.className?.toString(),
                            textSizeSp = calculatedSizeSp
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
            // If OCR fallback is enabled and accessibility tree exposed very few nodes (e.g. game/canvas)
            if (preferences.ocrFallbackEnabled.value && bitmap != null && items.size < 3) {
                val ocrBlocks = OcrFallbackHelper.recognizeText(bitmap)
                for (block in ocrBlocks) {
                    if (items.none { it.bounds.contains(block.bounds.centerX(), block.bounds.centerY()) }) {
                        items.add(
                            InspectableItem(
                                text = block.text,
                                bounds = block.bounds,
                                packageName = null
                            )
                        )
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
    }
}
