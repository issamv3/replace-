package com.example.service

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Optical & Typography Analyzer.
 * Extracts exact background color, text color, optical bounding box (tight ink rect),
 * optical height/width, optical baseline/center, and accurately calculates the original font weight
 * (Normal 400, Medium 500, SemiBold 600, Bold 700, ExtraBold 800, Black 900)
 * using pixel ink density and stroke thickness run-length analysis.
 */
object TextVisualAnalyzer {

    data class AnalysisResult(
        val backgroundColor: Int,
        val textColor: Int,
        val opticalBounds: Rect,
        val opticalCenterY: Float,
        val opticalHeightPx: Float,
        val opticalWidthPx: Float,
        val fontWeight: Int,
        val isBold: Boolean,
        val strokeWidthPx: Float,
        val inkDensity: Float
    )

    fun analyze(bitmap: Bitmap?, bounds: Rect): AnalysisResult {
        // Sensible fallbacks if screenshot is unavailable
        val defaultBg = Color.parseColor("#1E293B")
        val defaultText = Color.parseColor("#FFFFFF")
        val fallbackCenterY = bounds.centerY().toFloat()
        val fallbackHeight = bounds.height() * 0.72f
        val fallbackWidth = bounds.width().toFloat()

        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 || bounds.width() <= 0 || bounds.height() <= 0) {
            return AnalysisResult(
                backgroundColor = defaultBg,
                textColor = defaultText,
                opticalBounds = Rect(bounds),
                opticalCenterY = fallbackCenterY,
                opticalHeightPx = fallbackHeight,
                opticalWidthPx = fallbackWidth,
                fontWeight = 700, // Default to Bold for high visibility
                isBold = true,
                strokeWidthPx = 3f,
                inkDensity = 0.30f
            )
        }

        // Clamp bounds to bitmap boundaries
        val left = max(0, min(bounds.left, bitmap.width - 1))
        val top = max(0, min(bounds.top, bitmap.height - 1))
        val right = max(left + 1, min(bounds.right, bitmap.width))
        val bottom = max(top + 1, min(bounds.bottom, bitmap.height))

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            return AnalysisResult(
                backgroundColor = defaultBg,
                textColor = defaultText,
                opticalBounds = Rect(bounds),
                opticalCenterY = fallbackCenterY,
                opticalHeightPx = fallbackHeight,
                opticalWidthPx = fallbackWidth,
                fontWeight = 700,
                isBold = true,
                strokeWidthPx = 3f,
                inkDensity = 0.30f
            )
        }

        // 1. Sample border pixels to determine the dominant background color
        val borderPixels = mutableListOf<Int>()
        val stepX = max(1, width / 24)
        val stepY = max(1, height / 24)

        for (x in left until right step stepX) {
            borderPixels.add(bitmap.getPixel(x, top))
            borderPixels.add(bitmap.getPixel(x, bottom - 1))
        }
        for (y in top until bottom step stepY) {
            borderPixels.add(bitmap.getPixel(left, y))
            borderPixels.add(bitmap.getPixel(right - 1, y))
        }

        val backgroundColor = (if (borderPixels.isNotEmpty()) {
            findDominantColor(borderPixels)
        } else {
            bitmap.getPixel(left, top)
        }) or (0xFF shl 24)

        val bgLuminance = calculateLuminance(backgroundColor)

        // 2. Identify the text color by maximum contrast in interior pixels
        val interiorPixels = mutableListOf<Int>()
        val insetX = max(1, width / 8)
        val insetY = max(1, height / 8)
        val inLeft = (left + insetX).coerceAtMost(right - 1)
        val inRight = (right - insetX).coerceAtLeast(inLeft + 1)
        val inTop = (top + insetY).coerceAtMost(bottom - 1)
        val inBottom = (bottom - insetY).coerceAtLeast(inTop + 1)

        val sampleStep = max(1, min(width, height) / 20)
        for (y in inTop until inBottom step sampleStep) {
            for (x in inLeft until inRight step sampleStep) {
                interiorPixels.add(bitmap.getPixel(x, y))
            }
        }

        var bestTextColor = if (bgLuminance > 0.5) Color.BLACK else Color.WHITE
        var maxContrast = 0.0

        for (color in interiorPixels) {
            val lum = calculateLuminance(color)
            val contrast = abs(lum - bgLuminance)
            if (contrast > maxContrast && contrast > 0.18) {
                maxContrast = contrast
                bestTextColor = color
            }
        }
        val textColor = bestTextColor or (0xFF shl 24)
        val textLuminance = calculateLuminance(textColor)

        // 3. Scan the entire bounding box to compute the Optical Ink Bounds (tight rect of text)
        var minInkX = right
        var maxInkX = left
        var minInkY = bottom
        var maxInkY = top
        var totalInkCount = 0
        var totalSampledCount = 0

        // Contrast threshold between text and background: 35% of the total contrast
        val contrastThreshold = max(0.12, maxContrast * 0.35)

        val scanStep = max(1, min(width, height) / 80)
        val strokeRunsH = mutableListOf<Int>()

        for (y in top until bottom step scanStep) {
            var currentRun = 0
            for (x in left until right step scanStep) {
                totalSampledCount++
                val pixel = bitmap.getPixel(x, y)
                val lum = calculateLuminance(pixel)
                val diffWithBg = abs(lum - bgLuminance)
                val diffWithText = abs(lum - textLuminance)

                val isInk = diffWithBg >= contrastThreshold && diffWithText <= (1.0 - contrastThreshold)
                if (isInk) {
                    totalInkCount++
                    currentRun++
                    if (x < minInkX) minInkX = x
                    if (x > maxInkX) maxInkX = x
                    if (y < minInkY) minInkY = y
                    if (y > maxInkY) maxInkY = y
                } else {
                    if (currentRun > 0) {
                        strokeRunsH.add(currentRun * scanStep)
                        currentRun = 0
                    }
                }
            }
            if (currentRun > 0) {
                strokeRunsH.add(currentRun * scanStep)
            }
        }

        // Validate optical bounds
        val opticalBounds = if (minInkX < maxInkX && minInkY < maxInkY) {
            Rect(minInkX, minInkY, maxInkX + 1, maxInkY + 1)
        } else {
            Rect(bounds)
        }

        val opticalWidthPx = opticalBounds.width().toFloat()
        val opticalHeightPx = opticalBounds.height().toFloat()
        val opticalCenterY = opticalBounds.centerY().toFloat()

        // 4. Calculate Ink Density & Stroke Thickness
        val opticalArea = (opticalWidthPx * opticalHeightPx).coerceAtLeast(1f)
        val inkDensity = if (totalSampledCount > 0) {
            (totalInkCount.toFloat() / totalSampledCount.toFloat())
        } else 0.25f

        val avgStrokePx = if (strokeRunsH.isNotEmpty()) {
            strokeRunsH.filter { it in 1..((opticalHeightPx * 0.7f).toInt()) }.average().toFloat()
        } else {
            opticalHeightPx * 0.12f
        }

        val strokeRatio = if (opticalHeightPx > 0) (avgStrokePx / opticalHeightPx) else 0.12f

        // 5. Advanced Font Weight Determination:
        // Regular (400): thin stroke (strokeRatio < 0.10, inkDensity < 0.24)
        // Medium (500): strokeRatio 0.10..0.13
        // SemiBold (600): strokeRatio 0.13..0.16
        // Bold (700): heavy stroke (strokeRatio 0.16..0.21, inkDensity > 0.30)
        // Extra Bold (800): strokeRatio 0.21..0.26
        // Black / Heavy (900): very thick stroke (strokeRatio > 0.26, inkDensity > 0.40)
        val calculatedWeight = when {
            strokeRatio >= 0.25 || inkDensity >= 0.42f -> 900 // Black / Heavy
            strokeRatio >= 0.20 || inkDensity >= 0.35f -> 800 // Extra Bold
            strokeRatio >= 0.15 || inkDensity >= 0.28f -> 700 // Bold
            strokeRatio >= 0.12 || inkDensity >= 0.22f -> 600 // SemiBold
            strokeRatio >= 0.09 || inkDensity >= 0.18f -> 500 // Medium
            else -> 400 // Regular / Normal
        }

        val isBold = calculatedWeight >= 600

        return AnalysisResult(
            backgroundColor = backgroundColor,
            textColor = textColor,
            opticalBounds = opticalBounds,
            opticalCenterY = opticalCenterY,
            opticalHeightPx = opticalHeightPx,
            opticalWidthPx = opticalWidthPx,
            fontWeight = calculatedWeight,
            isBold = isBold,
            strokeWidthPx = avgStrokePx,
            inkDensity = inkDensity
        )
    }

    private fun findDominantColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.WHITE
        val counts = mutableMapOf<Int, Int>()
        for (p in pixels) {
            val r = (Color.red(p) shr 3) shl 3
            val g = (Color.green(p) shr 3) shl 3
            val b = (Color.blue(p) shr 3) shl 3
            val quantized = Color.rgb(r, g, b)
            counts[quantized] = (counts[quantized] ?: 0) + 1
        }
        val dominantQuantized = counts.maxByOrNull { it.value }?.key ?: pixels[0]
        return pixels.minByOrNull { p ->
            abs(Color.red(p) - Color.red(dominantQuantized)) +
                    abs(Color.green(p) - Color.green(dominantQuantized)) +
                    abs(Color.blue(p) - Color.blue(dominantQuantized))
        } ?: dominantQuantized
    }

    fun calculateLuminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
