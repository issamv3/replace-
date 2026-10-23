package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

data class ActiveTextPatch(
    val key: String,
    val bounds: Rect,
    val replacementText: String,
    val textColor: Int,
    val backgroundColor: Int,
    val textSizeSp: Float,
    val isBold: Boolean = false,
    val isItalic: Boolean = false
)

/**
 * High-performance full-screen non-touchable overlay canvas that renders all active text patches.
 * It erases original text by drawing the sampled background color, and renders replacement text
 * auto-fitted directly inside the exact node bounds.
 */
class TextOverlayContainerView(
    context: Context,
    private val windowManager: WindowManager
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val patches = mutableMapOf<String, ActiveTextPatch>()

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    val layoutParamsWindow: WindowManager.LayoutParams

    init {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsWindow = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    fun updatePatches(newPatches: Map<String, ActiveTextPatch>) {
        post {
            patches.clear()
            patches.putAll(newPatches)
            invalidate()
        }
    }

    fun addOrUpdatePatch(patch: ActiveTextPatch) {
        post {
            patches[patch.key] = patch
            invalidate()
        }
    }

    fun removePatch(key: String) {
        post {
            patches.remove(key)
            invalidate()
        }
    }

    fun clearAll() {
        post {
            patches.clear()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (patches.isEmpty()) return

        for ((_, patch) in patches) {
            val bounds = patch.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            // 1. Fill over original text using sampled background color (erases it completely)
            bgPaint.color = patch.backgroundColor
            canvas.drawRect(bounds, bgPaint)

            // 2. Render replacement text
            textPaint.color = patch.textColor
            val typefaceStyle = when {
                patch.isBold && patch.isItalic -> Typeface.BOLD_ITALIC
                patch.isBold -> Typeface.BOLD
                patch.isItalic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle)

            // Calculate auto-fitting font size so text never clips outside node bounds
            val baseTextSize = if (patch.textSizeSp > 0) patch.textSizeSp * density else bounds.height() * 0.65f
            var calculatedSize = baseTextSize
            textPaint.textSize = calculatedSize

            val availableWidth = max(10f, bounds.width() - 8f * density)
            val measuredWidth = textPaint.measureText(patch.replacementText)

            if (measuredWidth > availableWidth && measuredWidth > 0) {
                val scale = availableWidth / measuredWidth
                calculatedSize = max(8f * density, baseTextSize * scale)
                textPaint.textSize = calculatedSize
            }

            // Vertical centering calculation
            val fontMetrics = textPaint.fontMetrics
            val textHeight = fontMetrics.descent - fontMetrics.ascent
            val centerY = bounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
            val centerX = bounds.centerX().toFloat()

            canvas.drawText(patch.replacementText, centerX, centerY, textPaint)
        }
    }
}
