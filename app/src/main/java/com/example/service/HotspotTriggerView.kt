package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.data.AppPreferences
import kotlin.math.roundToInt

/**
 * A subtle, customizable trigger zone on the edge/corner of the screen.
 * When the floating bubble is hidden (via drop-to-delete or 5s hold),
 * tapping this hotspot instantly restores the floating bubble icon.
 */
@SuppressLint("ViewConstructor")
class HotspotTriggerView(
    context: Context,
    private val windowManager: WindowManager,
    private val onHotspotTapped: () -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    val layoutParamsWindow: WindowManager.LayoutParams
    private var isAttached = false
    private val triggerButton: FrameLayout
    private val triggerIcon: TextView

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val sizePx = (48 * density).roundToInt()

        layoutParamsWindow = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).roundToInt()
            y = (64 * density).roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        triggerButton = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC0F172A.toInt()) // 80% Slate 900
                setStroke((1.5f * density).roundToInt(), 0xFF38BDF8.toInt()) // Cyan border
            }
            elevation = 10f * density
        }

        triggerIcon = TextView(context).apply {
            text = "⇄"
            textSize = 18f
            setTextColor(0xFF38BDF8.toInt())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        triggerButton.addView(triggerIcon)
        addView(triggerButton)

        setupTouchListener()
        visibility = View.GONE
    }

    private val deleteTargetView = DeleteTargetView(context, windowManager)
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParamsWindow.x
                    initialY = layoutParamsWindow.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        if (!isDragging) {
                            isDragging = true
                            deleteTargetView.show()
                        }
                    }

                    if (isDragging) {
                        // For TOP/START or TOP/END
                        if ((layoutParamsWindow.gravity and Gravity.END) == Gravity.END) {
                            layoutParamsWindow.x = initialX - dx
                        } else {
                            layoutParamsWindow.x = initialX + dx
                        }
                        layoutParamsWindow.y = initialY + dy

                        try {
                            windowManager.updateViewLayout(this, layoutParamsWindow)
                        } catch (e: Exception) {
                            // View might not be attached
                        }

                        val isHovered = deleteTargetView.updateHover(event.rawX, event.rawY)
                        alpha = if (isHovered) 0.4f else 0.85f
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onHotspotTapped()
                    } else {
                        val hovered = deleteTargetView.isCurrentlyHovered()
                        deleteTargetView.hide()
                        deleteTargetView.reset()
                        if (hovered) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            hide()
                        } else {
                            alpha = 0.85f
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    deleteTargetView.hide()
                    alpha = 0.85f
                    true
                }
                else -> false
            }
        }
    }

    fun applyPosition(positionKey: String) {
        val marginX = (8 * density).roundToInt()
        val marginY = (64 * density).roundToInt()

        when (positionKey) {
            AppPreferences.HOTSPOT_TOP_RIGHT -> {
                layoutParamsWindow.gravity = Gravity.TOP or Gravity.END
                layoutParamsWindow.x = marginX
                layoutParamsWindow.y = marginY
            }
            AppPreferences.HOTSPOT_TOP_LEFT -> {
                layoutParamsWindow.gravity = Gravity.TOP or Gravity.START
                layoutParamsWindow.x = marginX
                layoutParamsWindow.y = marginY
            }
            AppPreferences.HOTSPOT_BOTTOM_RIGHT -> {
                layoutParamsWindow.gravity = Gravity.BOTTOM or Gravity.END
                layoutParamsWindow.x = marginX
                layoutParamsWindow.y = (100 * density).roundToInt()
            }
            AppPreferences.HOTSPOT_BOTTOM_LEFT -> {
                layoutParamsWindow.gravity = Gravity.BOTTOM or Gravity.START
                layoutParamsWindow.x = marginX
                layoutParamsWindow.y = (100 * density).roundToInt()
            }
            else -> {
                layoutParamsWindow.gravity = Gravity.TOP or Gravity.END
                layoutParamsWindow.x = marginX
                layoutParamsWindow.y = marginY
            }
        }

        if (isAttached) {
            try {
                windowManager.updateViewLayout(this, layoutParamsWindow)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun show() {
        if (!isAttached) {
            try {
                windowManager.addView(this, layoutParamsWindow)
                isAttached = true
            } catch (e: Exception) {
                // Ignore
            }
        }
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.6f
        scaleY = 0.6f
        animate()
            .alpha(0.85f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .start()
    }

    fun hide() {
        animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(160)
            .withEndAction {
                visibility = View.GONE
                if (isAttached) {
                    try {
                        windowManager.removeView(this)
                        isAttached = false
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            .start()
    }
}
