package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a saved text replacement rule.
 */
@Entity(tableName = "replacement_rules")
data class ReplacementRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val replacementText: String,
    val packageName: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xFF1E293B.toInt(),
    val textSizeSp: Float = 16f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isEnabled: Boolean = true,
    val matchMode: String = MATCH_EXACT, // MATCH_EXACT, MATCH_CONTAINS, MATCH_VIEW_ID
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val widthScale: Float = 1.0f,
    val fontWeight: Int = 700,
    val lastUsedTimestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MATCH_EXACT = "EXACT"
        const val MATCH_CONTAINS = "CONTAINS"
        const val MATCH_VIEW_ID = "VIEW_ID"
    }
}
