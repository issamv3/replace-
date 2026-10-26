package com.example.service

import android.content.Context
import android.graphics.Canvas
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
    val textSizeSp: Float = 0f,
    val widthScale: Float = 1.0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val hasRealText: Boolean = true,
    val className: String? = null,
    val fontWeight: Int = 700,
    val opticalHeight: Float = 0f,
    val opticalWidth: Float = 0f,
    val opticalCenterY: Float = 0f
)

/**
 * High-performance full-screen non-touchable overlay canvas that renders active text patches.
 * Uses advanced typography calibration:
 * 1. Matches exact original font weight (Regular 400 to Black 900) and stroke thickness.
 * 2. Matches exact optical glyph height (cap-height) so numbers are neither too big nor too small.
 * 3. Matches exact original width using proportional textScaleX.
 * 4. Aligns precisely to the original text's baseline and optical center.
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val drawBounds = Rect()
    private val measuredGlyphBounds = Rect()

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

            // 1. Fill over original text using sampled background color (cleanly covers old text)
            bgPaint.color = patch.backgroundColor
            drawBounds.set(bounds)
            // Add 1px margin around bounds to avoid any pixel leakage of original text
            drawBounds.inset(-1, -1)
            canvas.drawRect(drawBounds, bgPaint)

            // 2. Exact Font Weight (وزن الخط) Configuration
            val weight = patch.fontWeight.coerceIn(100, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, weight, patch.isItalic)
            } else {
                val style = when {
                    (weight >= 600 || patch.isBold) && patch.isItalic -> Typeface.BOLD_ITALIC
                    weight >= 600 || patch.isBold -> Typeface.BOLD
                    patch.isItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, style)
            }

            // Ensure heavy/bold weights are thick and never thin (رقيق)
            textPaint.isFakeBoldText = (weight >= 600 || patch.isBold)
            textPaint.color = patch.textColor

            // 3. Exact Font Size Matching (Cap-height calibration)
            // Target physical digit height:
            val targetInkHeight = if (patch.opticalHeight > 0f) {
                patch.opticalHeight
            } else {
                bounds.height() * 0.72f
            }.coerceAtLeast(6f * density)

            // Calibrate text size so glyphs equal targetInkHeight
            var initialSize = targetInkHeight * 1.35f
            textPaint.textSize = initialSize
            textPaint.textScaleX = 1.0f

            textPaint.getTextBounds("20260945", 0, 8, measuredGlyphBounds)
            val currentGlyphHeight = measuredGlyphBounds.height().toFloat()
            if (currentGlyphHeight > 0f) {
                val heightRatio = targetInkHeight / currentGlyphHeight
                initialSize = (initialSize * heightRatio).coerceAtLeast(6f * density)
                textPaint.textSize = initialSize
            }

            // 4. Exact Text Width Matching (عرض النص)
            val availableWidth = bounds.width().toFloat()
            val naturalTextWidth = textPaint.measureText(patch.replacementText)

            val autoWidthScale = if (naturalTextWidth > 0f && availableWidth > 0f) {
                // Proportional scale to span exact original width
                (availableWidth / naturalTextWidth).coerceIn(0.5f, 2.2f)
            } else 1.0f

            // Combine auto-fit width with user's widthScale setting
            val effectiveScaleX = autoWidthScale * patch.widthScale
            textPaint.textScaleX = effectiveScaleX.coerceIn(0.3f, 3.0f)

            // 5. Exact Vertical Alignment & Baseline Calculation (المكان بالضبط)
            textPaint.getTextBounds(patch.replacementText, 0, patch.replacementText.length, measuredGlyphBounds)
            val glyphCenterY = (measuredGlyphBounds.top + measuredGlyphBounds.bottom) / 2f
            val targetCenterY = if (patch.opticalCenterY > 0f) {
                patch.opticalCenterY
            } else {
                bounds.centerY().toFloat()
            }
            val baselineY = targetCenterY - glyphCenterY

            // 6. Stroke simulation for Extra Bold / Black weights (800 - 900) if requested
            if (weight >= 800) {
                strokePaint.color = patch.textColor
                strokePaint.typeface = textPaint.typeface
                strokePaint.textSize = textPaint.textSize
                strokePaint.textScaleX = textPaint.textScaleX
                strokePaint.strokeWidth = ((weight - 700) / 300f * density).coerceIn(0.6f * density, 2.5f * density)
            }

            // 7. Horizontal Placement
            val finalRenderedWidth = textPaint.measureText(patch.replacementText)
            val startX = bounds.left + (availableWidth - finalRenderedWidth) / 2f

            textPaint.textAlign = Paint.Align.LEFT
            strokePaint.textAlign = Paint.Align.LEFT

            // Draw stroke outline first if extra-heavy
            if (weight >= 800) {
                canvas.drawText(patch.replacementText, startX, baselineY, strokePaint)
            }

            // Draw main text
            canvas.drawText(patch.replacementText, startX, baselineY, textPaint)
        }
    }
}
