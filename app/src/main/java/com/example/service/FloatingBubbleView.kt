package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.AppPreferences
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val windowManager: WindowManager,
    private val preferences: AppPreferences,
    private val onAddReplacementClicked: () -> Unit,
    private val onToggleAutoApplyClicked: () -> Unit,
    private val onBubbleDismissed: () -> Unit
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isExpanded = false

    private val rootRow: LinearLayout
    private val bubbleContainer: FrameLayout
    private val bubbleText: TextView
    private val menuContainer: LinearLayout
    private val btnAdd: FrameLayout
    private val btnPlay: FrameLayout
    private val playIconText: TextView

    private val bubbleSizePx: Int
        get() = (preferences.bubbleSizeDp.value * density).roundToInt()

    val layoutParamsWindow: WindowManager.LayoutParams

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsWindow = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.bubblePosX.value
            y = preferences.bubblePosY.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        clipChildren = false
        clipToPadding = false

        rootRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // Main Bubble - distinctive swap symbol (⇄) instead of generic pencil
        val currentSize = bubbleSizePx
        bubbleContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(currentSize, currentSize)
            background = createBubbleBackground(0xFF0F172A.toInt(), 0xFF38BDF8.toInt())
            elevation = 16f * density
        }

        bubbleText = TextView(context).apply {
            text = "⇄"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubbleContainer.addView(bubbleText)

        // Expandable Menu container
        menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val margin = (8 * density).roundToInt()
            setPadding(margin, 0, margin, 0)
            clipChildren = false
        }

        // "+" Add Replacement Button
        val actionBtnSize = (currentSize * 0.88f).roundToInt()
        btnAdd = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize).apply {
                marginEnd = (6 * density).roundToInt()
            }
            background = createBubbleBackground(0xFF0284C7.toInt(), 0xFF7DD3FC.toInt())
            elevation = 14f * density
            val addText = TextView(context).apply {
                text = "+"
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(addText)
            setOnClickListener {
                collapseMenu()
                onAddReplacementClicked()
            }
        }

        // "▶" / "⏸" Play / Pause Button
        btnPlay = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize)
            background = createBubbleBackground(0xFF10B981.toInt(), 0xFF6EE7B7.toInt())
            elevation = 14f * density
            playIconText = TextView(context).apply {
                text = if (preferences.isAutoApplyActive.value) "⏸" else "▶"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(playIconText)
            setOnClickListener {
                onToggleAutoApplyClicked()
                updatePlayState(preferences.isAutoApplyActive.value)
            }
        }

        menuContainer.addView(btnAdd)
        menuContainer.addView(btnPlay)

        // Initial setup depending on whether bubble was saved on left or right edge
        val screenWidth = getScreenWidth()
        if (layoutParamsWindow.x + currentSize / 2 >= screenWidth / 2) {
            rootRow.addView(menuContainer)
            rootRow.addView(bubbleContainer)
        } else {
            rootRow.addView(bubbleContainer)
            rootRow.addView(menuContainer)
        }

        addView(rootRow)

        applyOpacity(preferences.bubbleOpacity.value)
        setupTouchListener()
    }

    private fun getScreenWidth(): Int {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return displayMetrics.widthPixels
    }

    fun updatePlayState(isPlaying: Boolean) {
        post {
            playIconText.text = if (isPlaying) "⏸" else "▶"
            val bgColor = if (isPlaying) 0xFFE11D48.toInt() else 0xFF10B981.toInt()
            val strokeColor = if (isPlaying) 0xFFFDA4AF.toInt() else 0xFF6EE7B7.toInt()
            btnPlay.background = createBubbleBackground(bgColor, strokeColor)
        }
    }

    fun applyOpacity(opacity: Float) {
        val finalAlpha = if (isExpanded) 1.0f else opacity.coerceIn(0.2f, 1.0f)
        bubbleContainer.alpha = finalAlpha
    }

    private fun createBubbleBackground(color: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * density).roundToInt(), strokeColor)
        }
    }

    private val deleteTargetView = DeleteTargetView(context, windowManager)
    private var holdRunnable: Runnable? = null
    private var isHoldingForDismiss = false

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        bubbleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParamsWindow.x
                    initialY = layoutParamsWindow.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    applyOpacity(1.0f)

                    startHoldTimer()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (!isDragging) {
                            isDragging = true
                            cancelHoldTimer()
                            deleteTargetView.show()
                            if (isExpanded) {
                                collapseMenu()
                            }
                        }
                    }

                    if (isDragging) {
                        layoutParamsWindow.x = initialX + dx
                        layoutParamsWindow.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(this, layoutParamsWindow)
                        } catch (e: Exception) {
                            // View might not be attached
                        }

                        val isHovered = deleteTargetView.updateHover(event.rawX, event.rawY)
                        bubbleContainer.alpha = if (isHovered) 0.45f else 1.0f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelHoldTimer()
                    if (!isDragging) {
                        // Click event -> toggle menu
                        toggleMenu()
                        applyOpacity(preferences.bubbleOpacity.value)
                    } else {
                        val hovered = deleteTargetView.isCurrentlyHovered()
                        deleteTargetView.hide()
                        deleteTargetView.reset()
                        if (hovered) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            animateDisappearAndDismiss()
                        } else {
                            snapToEdge()
                            applyOpacity(preferences.bubbleOpacity.value)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelHoldTimer()
                    deleteTargetView.hide()
                    applyOpacity(preferences.bubbleOpacity.value)
                    true
                }
                else -> false
            }
        }
    }

    private fun startHoldTimer() {
        isHoldingForDismiss = true
        // Smoothly scale bubble up slightly over 5 seconds to provide visual countdown feedback
        bubbleContainer.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .setDuration(5000L)
            .start()

        holdRunnable = Runnable {
            if (isHoldingForDismiss && !isDragging) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                animateDisappearAndDismiss()
            }
        }
        postDelayed(holdRunnable, 5000L)
    }

    private fun cancelHoldTimer() {
        if (isHoldingForDismiss) {
            isHoldingForDismiss = false
            holdRunnable?.let { removeCallbacks(it) }
            holdRunnable = null
            bubbleContainer.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(160)
                .start()
        }
    }

    private fun animateDisappearAndDismiss() {
        bubbleContainer.animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                visibility = View.GONE
                deleteTargetView.hide()
                onBubbleDismissed()
            }
            .start()
    }

    fun showBubble() {
        visibility = View.VISIBLE
        bubbleContainer.scaleX = 0.5f
        bubbleContainer.scaleY = 0.5f
        bubbleContainer.alpha = 0f
        applyOpacity(preferences.bubbleOpacity.value)
        bubbleContainer.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(preferences.bubbleOpacity.value)
            .setDuration(220)
            .start()
    }

    fun hideBubble() {
        cancelHoldTimer()
        deleteTargetView.hide()
        visibility = View.GONE
    }

    private fun toggleMenu() {
        if (isExpanded) {
            collapseMenu()
        } else {
            expandMenu()
        }
    }

    private fun expandMenu() {
        isExpanded = true
        bubbleContainer.alpha = 1.0f
        updatePlayState(preferences.isAutoApplyActive.value)

        val screenWidth = getScreenWidth()
        val currentSize = bubbleSizePx
        val isOnRight = (layoutParamsWindow.x + currentSize / 2) >= screenWidth / 2

        rootRow.removeAllViews()
        menuContainer.visibility = View.VISIBLE

        if (isOnRight) {
            // Put menu on the LEFT of bubble so buttons appear on screen!
            rootRow.addView(menuContainer)
            rootRow.addView(bubbleContainer)

            // Measure menu to position the window properly on the right
            menuContainer.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val menuWidth = menuContainer.measuredWidth
            val totalWidth = menuWidth + currentSize + (16 * density).roundToInt()
            layoutParamsWindow.x = max(8, screenWidth - totalWidth)
        } else {
            // Put menu on the RIGHT of bubble
            rootRow.addView(bubbleContainer)
            rootRow.addView(menuContainer)
            layoutParamsWindow.x = (16 * density).roundToInt()
        }

        try {
            windowManager.updateViewLayout(this, layoutParamsWindow)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false
        menuContainer.visibility = View.GONE

        val screenWidth = getScreenWidth()
        val currentSize = bubbleSizePx
        val isOnRight = (layoutParamsWindow.x + currentSize / 2) >= screenWidth / 2
        val margin = (16 * density).roundToInt()

        if (isOnRight) {
            layoutParamsWindow.x = screenWidth - currentSize - margin
        } else {
            layoutParamsWindow.x = margin
        }

        try {
            windowManager.updateViewLayout(this, layoutParamsWindow)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        applyOpacity(preferences.bubbleOpacity.value)
    }

    private fun snapToEdge() {
        val screenWidth = getScreenWidth()
        val currentSize = bubbleSizePx
        val margin = (16 * density).roundToInt()

        val isSnapRight = (layoutParamsWindow.x + currentSize / 2) >= screenWidth / 2
        val targetX = if (isSnapRight) {
            screenWidth - currentSize - margin
        } else {
            margin
        }

        val startX = layoutParamsWindow.x
        val animator = ValueAnimator.ofInt(startX, targetX)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            layoutParamsWindow.x = anim.animatedValue as Int
            try {
                windowManager.updateViewLayout(this, layoutParamsWindow)
            } catch (e: Exception) {
                // Ignore if detached
            }
        }
        animator.start()

        preferences.setBubblePosition(targetX, layoutParamsWindow.y)
    }
}
