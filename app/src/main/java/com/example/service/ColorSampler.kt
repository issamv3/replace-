package com.example.service

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Samples background and text colors from a screenshot bitmap at the specified node bounds.
 */
object ColorSampler {

    data class SampledColors(
        val backgroundColor: Int,
        val textColor: Int
    )

    fun sample(bitmap: Bitmap?, bounds: Rect): SampledColors {
        // Fallback default colors if bitmap is unavailable or FLAG_SECURE blocked it
        val defaultBg = Color.parseColor("#1E293B")
        val defaultText = Color.parseColor("#FFFFFF")

        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return SampledColors(defaultBg, defaultText)
        }

        // Clip bounds to bitmap dimensions
        val left = max(0, min(bounds.left, bitmap.width - 1))
        val top = max(0, min(bounds.top, bitmap.height - 1))
        val right = max(left + 1, min(bounds.right, bitmap.width))
        val bottom = max(top + 1, min(bounds.bottom, bitmap.height))

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            return SampledColors(defaultBg, defaultText)
        }

        // Sample border pixels to determine the background color
        val borderPixels = mutableListOf<Int>()
        val stepX = max(1, width / 20)
        val stepY = max(1, height / 20)

        // Top and bottom edges
        for (x in left until right step stepX) {
            borderPixels.add(bitmap.getPixel(x, top))
            borderPixels.add(bitmap.getPixel(x, bottom - 1))
        }
        // Left and right edges
        for (y in top until bottom step stepY) {
            borderPixels.add(bitmap.getPixel(left, y))
            borderPixels.add(bitmap.getPixel(right - 1, y))
        }

        val backgroundColor = if (borderPixels.isNotEmpty()) {
            findDominantColor(borderPixels)
        } else {
            bitmap.getPixel(left, top)
        }

        // Now find the contrasting text color from interior pixels
        val interiorPixels = mutableListOf<Int>()
        val insetX = max(1, width / 6)
        val insetY = max(1, height / 6)
        val inLeft = (left + insetX).coerceAtMost(right - 1)
        val inRight = (right - insetX).coerceAtLeast(inLeft + 1)
        val inTop = (top + insetY).coerceAtMost(bottom - 1)
        val inBottom = (bottom - insetY).coerceAtLeast(inTop + 1)

        val sampleStep = max(1, min(width, height) / 15)
        for (y in inTop until inBottom step sampleStep) {
            for (x in inLeft until inRight step sampleStep) {
                interiorPixels.add(bitmap.getPixel(x, y))
            }
        }

        // Find the pixel with maximum contrast to background
        val bgLuminance = calculateLuminance(backgroundColor)
        var bestTextColor = if (bgLuminance > 0.5) Color.BLACK else Color.WHITE
        var maxContrast = 0.0

        for (color in interiorPixels) {
            val lum = calculateLuminance(color)
            val contrast = abs(lum - bgLuminance)
            if (contrast > maxContrast && contrast > 0.2) {
                maxContrast = contrast
                bestTextColor = color
            }
        }

        return SampledColors(
            backgroundColor = backgroundColor or (0xFF shl 24), // Ensure fully opaque background
            textColor = bestTextColor or (0xFF shl 24)
        )
    }

    private fun findDominantColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.WHITE
        // Group by quantized RGB to cluster similar colors
        val counts = mutableMapOf<Int, Int>()
        for (p in pixels) {
            // Quantize to 5 bits per channel to smooth slight compression artifacts
            val r = (Color.red(p) shr 3) shl 3
            val g = (Color.green(p) shr 3) shl 3
            val b = (Color.blue(p) shr 3) shl 3
            val quantized = Color.rgb(r, g, b)
            counts[quantized] = (counts[quantized] ?: 0) + 1
        }
        val dominantQuantized = counts.maxByOrNull { it.value }?.key ?: pixels[0]

        // Find the actual pixel closest to the dominant quantized center
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
