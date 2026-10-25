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
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Overlay view displayed at the bottom of the screen when dragging the floating bubble icon.
 * Dragging the bubble onto this "X" target and releasing dismisses / deletes the bubble.
 */
@SuppressLint("ViewConstructor")
class DeleteTargetView(
    context: Context,
    private val windowManager: WindowManager
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    val layoutParamsWindow: WindowManager.LayoutParams

    private val targetCircle: FrameLayout
    private val iconText: TextView
    private val hintText: TextView
    private val container: LinearLayout

    private var isHovered = false
    private var isAttached = false
    private val targetSizePx = (64 * density).roundToInt()
    private val hitRadiusPx = (80 * density).roundToInt()

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsWindow = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (140 * density).roundToInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * density).roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Circular "X" Target
        targetCircle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(targetSizePx, targetSizePx)
            background = createCircleBackground(false)
            elevation = 12f * density
        }

        iconText = TextView(context).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        targetCircle.addView(iconText)

        hintText = TextView(context).apply {
            text = "Drag here to hide"
            textSize = 12f
            setTextColor(0xFFCBD5E1.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).roundToInt(), 0, 0)
        }

        container.addView(targetCircle)
        container.addView(hintText)

        val lpContainer = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        addView(container, lpContainer)

        alpha = 0f
        visibility = View.GONE
    }

    private fun createCircleBackground(hovered: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (hovered) {
                setColor(0xFFEF4444.toInt()) // Vivid Red (Tailwind Red 500)
                setStroke((3 * density).roundToInt(), Color.WHITE)
            } else {
                setColor(0xCC1E293B.toInt()) // Slate 800 with 80% opacity
                setStroke((2 * density).roundToInt(), 0xFF94A3B8.toInt())
            }
        }
    }

    fun show() {
        if (!isAttached) {
            try {
                windowManager.addView(this, layoutParamsWindow)
                isAttached = true
            } catch (e: Exception) {
                // Ignore if already added
            }
        }
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(180).setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    fun hide() {
        isHovered = false
        animate().alpha(0f).setDuration(150).withEndAction {
            visibility = View.GONE
            if (isAttached) {
                try {
                    windowManager.removeView(this)
                    isAttached = false
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }.start()
    }

    /**
     * Checks if the floating coordinates are within the drop-to-delete target zone.
     * Uses a generous detection radius and bottom-center zone to ensure easy dismissal.
     */
    fun updateHover(rawX: Float, rawY: Float): Boolean {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Center of the X target located at the bottom center
        val targetCenterX = screenWidth / 2f
        val targetCenterY = screenHeight - (75 * density)

        val distance = hypot((rawX - targetCenterX).toDouble(), (rawY - targetCenterY).toDouble()).toFloat()

        // Generous detection: either close to the target center OR anywhere in the bottom-center drop zone
        val inBottomZone = rawY >= (screenHeight - 170 * density) &&
                abs(rawX - targetCenterX) <= (150 * density)

        val hovering = distance <= (125 * density) || inBottomZone

        if (hovering != isHovered) {
            isHovered = hovering
            targetCircle.background = createCircleBackground(hovering)
            if (hovering) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                targetCircle.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120).start()
                hintText.text = "Release to delete from screen"
                hintText.setTextColor(0xFFEF4444.toInt())
            } else {
                targetCircle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                hintText.text = "Drag here to delete"
                hintText.setTextColor(0xFFCBD5E1.toInt())
            }
        }
        return isHovered
    }

    fun isCurrentlyHovered(): Boolean = isHovered

    fun reset() {
        isHovered = false
        targetCircle.scaleX = 1.0f
        targetCircle.scaleY = 1.0f
        targetCircle.background = createCircleBackground(false)
    }
}
