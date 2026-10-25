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

data class ActiveTextPatch(
    val key: String,
    val bounds: Rect,
    val replacementText: String,
    val textColor: Int,
    val backgroundColor: Int,
    val textSizeSp: Float = 0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false
)

/**
 * High-performance full-screen non-touchable overlay canvas that renders active text patches.
 * It dynamically tracks on-screen text nodes, erases original text using the sampled background color,
 * and renders replacement text with the exact font size and alignment of the original word.
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun updatePatches(newPatches: Map<String, ActiveTextPatch>) {
        post {
            patches.clear()
            patches.putAll(newPatches)
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

            // 2. Configure font style
            textPaint.color = patch.textColor
            val typefaceStyle = when {
                patch.isBold && patch.isItalic -> Typeface.BOLD_ITALIC
                patch.isBold -> Typeface.BOLD
                patch.isItalic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle)

            // 3. Exact font size matching the original word:
            // Standard single-line UI text font size is 72% of the bounding box height
            val targetSizePx = (bounds.height() * 0.72f).coerceAtLeast(8f * density)
            textPaint.textSize = targetSizePx

            // Auto-scale width only if replacement text is longer than the original box
            val availableWidth = max(10f, bounds.width() - 4f * density)
            val measuredWidth = textPaint.measureText(patch.replacementText)
            if (measuredWidth > availableWidth && measuredWidth > 0) {
                val scale = availableWidth / measuredWidth
                textPaint.textSize = max(8f * density, targetSizePx * scale)
            }

            // Vertical centering calculation within bounds
            val fontMetrics = textPaint.fontMetrics
            val centerY = bounds.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f

            // Alignment: if box is wide (left-aligned text), align left with padding; otherwise center
            val currentTextWidth = textPaint.measureText(patch.replacementText)
            if (availableWidth > currentTextWidth * 1.35f) {
                textPaint.textAlign = Paint.Align.LEFT
                val startX = bounds.left + 4f * density
                canvas.drawText(patch.replacementText, startX, centerY, textPaint)
            } else {
                textPaint.textAlign = Paint.Align.CENTER
                val centerX = bounds.centerX().toFloat()
                canvas.drawText(patch.replacementText, centerX, centerY, textPaint)
            }
        }
    }
}
