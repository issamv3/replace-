package com.example.service

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

data class DateMatch(
    val dateText: String,
    val startIndex: Int,
    val endIndex: Int,
    val bounds: Rect
)

/**
 * Utility to identify, locate, and extract dates and timestamps on screen
 * specifically matching formats like '2026-10-23 09:45'.
 */
object DateDetector {

    // Regex matching dates & timestamps:
    // 1. YYYY-MM-DD or YYYY/MM/DD or YYYY.MM.DD with optional HH:mm(:ss) (e.g. 2026-10-23 09:45)
    // 2. DD-MM-YYYY or MM/DD/YYYY with optional HH:mm(:ss)
    // 3. Standalone times (e.g. 09:45, 09:45:00, 9:45 AM)
    private val DATE_TIME_REGEX = Regex(
        "\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4})" +
        "(?:[T\\s]+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM|am|pm))?)?\\b" +
        "|\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM|am|pm))?\\b"
    )

    /**
     * Finds all date/timestamp occurrences in the text and calculates
     * their exact sub-bounding boxes within the parent node's bounds.
     */
    fun findDates(text: String, parentBounds: Rect, density: Float): List<DateMatch> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return emptyList()

        val matches = DATE_TIME_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val totalLen = max(1, text.length)
        val parentWidth = parentBounds.width()

        return matches.map { match ->
            val startIdx = match.range.first
            val endIdx = match.range.last + 1
            val dateStr = match.value.trim()

            // Calculate precise horizontal position of the date inside the parent text bounding box
            val startFraction = (startIdx.toFloat() / totalLen).coerceIn(0f, 1f)
            val endFraction = (endIdx.toFloat() / totalLen).coerceIn(startFraction, 1f)

            val left = parentBounds.left + (parentWidth * startFraction).toInt()
            val right = max(
                left + (28 * density).toInt(),
                parentBounds.left + (parentWidth * endFraction).toInt()
            )

            val dateBounds = Rect(
                left.coerceIn(parentBounds.left, parentBounds.right),
                parentBounds.top,
                min(right, max(left + (28 * density).toInt(), parentBounds.right)),
                parentBounds.bottom
            )

            DateMatch(
                dateText = dateStr,
                startIndex = startIdx,
                endIndex = endIdx,
                bounds = dateBounds
            )
        }
    }

    /**
     * Calculates the exact sub-bounds of a target date string within a parent bounding box.
     */
    fun calculateDateSubBounds(
        fullText: String,
        targetDate: String,
        parentBounds: Rect,
        density: Float
    ): Rect {
        val startIdx = fullText.indexOf(targetDate, ignoreCase = true)
        if (startIdx < 0) return Rect(parentBounds)

        val endIdx = startIdx + targetDate.length
        val totalLen = max(1, fullText.length)
        val parentWidth = parentBounds.width()

        val startFraction = (startIdx.toFloat() / totalLen).coerceIn(0f, 1f)
        val endFraction = (endIdx.toFloat() / totalLen).coerceIn(startFraction, 1f)

        val left = parentBounds.left + (parentWidth * startFraction).toInt()
        val right = max(
            left + (24 * density).toInt(),
            parentBounds.left + (parentWidth * endFraction).toInt()
        )

        return Rect(
            left.coerceIn(parentBounds.left, parentBounds.right),
            parentBounds.top,
            min(right, max(left + (24 * density).toInt(), parentBounds.right)),
            parentBounds.bottom
        )
    }

    /**
     * Checks if text contains a date matching patterns like 2026-10-23 09:45.
     */
    fun containsDate(text: String): Boolean {
        return DATE_TIME_REGEX.containsMatchIn(text)
    }

    /**
     * Returns current date and time formatted as yyyy-MM-dd HH:mm.
     */
    fun getCurrentDateTimeString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    /**
     * Adjusts a date string by a given field (e.g. Calendar.DAY_OF_YEAR) and amount.
     */
    fun adjustDate(baseStr: String, calendarField: Int, amount: Int): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        try {
            cal.time = sdf.parse(baseStr) ?: java.util.Date()
        } catch (e: Exception) {
            cal.time = java.util.Date()
        }
        cal.add(calendarField, amount)
        return sdf.format(cal.time)
    }
}
