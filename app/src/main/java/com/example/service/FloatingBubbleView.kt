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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.AppPreferences
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val windowManager: WindowManager,
    private val preferences: AppPreferences,
    private val onAddReplacementClicked: () -> Unit,
    private val onToggleAutoApplyClicked: () -> Unit
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isExpanded = false

    private val bubbleContainer: FrameLayout
    private val bubbleIcon: ImageView
    private val menuContainer: LinearLayout
    private val btnAdd: FrameLayout
    private val btnPlay: FrameLayout
    private val playIconText: TextView

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
        }

        val density = context.resources.displayMetrics.density
        val bubbleSizePx = (preferences.bubbleSizeDp.value * density).roundToInt()

        // Root container
        clipChildren = false
        clipToPadding = false

        val rootRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }

        // Main Bubble
        bubbleContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            background = createBubbleBackground(0xFF0F172A.toInt(), 0xFF38BDF8.toInt())
            elevation = 16f * density
        }

        bubbleIcon = ImageView(context).apply {
            val iconPadding = (bubbleSizePx * 0.22f).roundToInt()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(0xFFF8FAFC.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubbleContainer.addView(bubbleIcon)

        // Expandable Menu
        menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val margin = (8 * density).roundToInt()
            setPadding(margin, 0, margin, 0)
        }

        // "+" Add Replacement Button
        val actionBtnSize = (bubbleSizePx * 0.88f).roundToInt()
        btnAdd = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize).apply {
                marginEnd = (6 * density).roundToInt()
            }
            background = createBubbleBackground(0xFF0284C7.toInt(), 0xFF7DD3FC.toInt())
            elevation = 12f * density
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

        // "▶" Play / Apply Button
        btnPlay = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize)
            background = createBubbleBackground(0xFF10B981.toInt(), 0xFF6EE7B7.toInt())
            elevation = 12f * density
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

        rootRow.addView(bubbleContainer)
        rootRow.addView(menuContainer)
        addView(rootRow)

        applyOpacity(preferences.bubbleOpacity.value)
        setupTouchListener()
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
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * density).roundToInt(), strokeColor)
        }
    }

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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        if (isExpanded) {
                            collapseMenu()
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Click event -> toggle menu
                        toggleMenu()
                    } else {
                        // Drag released -> snap to nearest edge
                        snapToEdge()
                    }
                    applyOpacity(preferences.bubbleOpacity.value)
                    true
                }
                else -> false
            }
        }
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
        menuContainer.visibility = View.VISIBLE
        bubbleContainer.alpha = 1.0f
        updatePlayState(preferences.isAutoApplyActive.value)
    }

    fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false
        menuContainer.visibility = View.GONE
        applyOpacity(preferences.bubbleOpacity.value)
    }

    private fun snapToEdge() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val viewWidth = bubbleContainer.width.coerceAtLeast(100)

        val targetX = if (layoutParamsWindow.x + viewWidth / 2 < screenWidth / 2) {
            16 // Snap to left
        } else {
            screenWidth - viewWidth - 16 // Snap to right
        }

        val startX = layoutParamsWindow.x
        val animator = ValueAnimator.ofInt(startX, targetX)
        animator.duration = 250
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
