package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("text_shift_prefs", Context.MODE_PRIVATE)

    private val _bubbleOpacity = MutableStateFlow(prefs.getFloat(KEY_BUBBLE_OPACITY, 0.75f))
    val bubbleOpacity: StateFlow<Float> = _bubbleOpacity.asStateFlow()

    private val _bubbleSizeDp = MutableStateFlow(prefs.getInt(KEY_BUBBLE_SIZE_DP, 56))
    val bubbleSizeDp: StateFlow<Int> = _bubbleSizeDp.asStateFlow()

    private val _ocrFallbackEnabled = MutableStateFlow(prefs.getBoolean(KEY_OCR_FALLBACK, false))
    val ocrFallbackEnabled: StateFlow<Boolean> = _ocrFallbackEnabled.asStateFlow()

    private val _isAutoApplyActive = MutableStateFlow(prefs.getBoolean(KEY_AUTO_APPLY, true))
    val isAutoApplyActive: StateFlow<Boolean> = _isAutoApplyActive.asStateFlow()

    private val _bubblePosX = MutableStateFlow(prefs.getInt(KEY_BUBBLE_POS_X, 0))
    val bubblePosX: StateFlow<Int> = _bubblePosX.asStateFlow()

    private val _bubblePosY = MutableStateFlow(prefs.getInt(KEY_BUBBLE_POS_Y, 400))
    val bubblePosY: StateFlow<Int> = _bubblePosY.asStateFlow()

    private val _debounceMs = MutableStateFlow(prefs.getLong(KEY_DEBOUNCE_MS, 120L))
    val debounceMs: StateFlow<Long> = _debounceMs.asStateFlow()

    fun setBubbleOpacity(value: Float) {
        val clamped = value.coerceIn(0.2f, 1.0f)
        prefs.edit().putFloat(KEY_BUBBLE_OPACITY, clamped).apply()
        _bubbleOpacity.value = clamped
    }

    fun setBubbleSizeDp(value: Int) {
        val clamped = value.coerceIn(40, 72)
        prefs.edit().putInt(KEY_BUBBLE_SIZE_DP, clamped).apply()
        _bubbleSizeDp.value = clamped
    }

    fun setOcrFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OCR_FALLBACK, enabled).apply()
        _ocrFallbackEnabled.value = enabled
    }

    fun setAutoApplyActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_APPLY, active).apply()
        _isAutoApplyActive.value = active
    }

    fun setBubblePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BUBBLE_POS_X, x).putInt(KEY_BUBBLE_POS_Y, y).apply()
        _bubblePosX.value = x
        _bubblePosY.value = y
    }

    fun setDebounceMs(ms: Long) {
        prefs.edit().putLong(KEY_DEBOUNCE_MS, ms).apply()
        _debounceMs.value = ms
    }

    companion object {
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        private const val KEY_OCR_FALLBACK = "ocr_fallback"
        private const val KEY_AUTO_APPLY = "auto_apply"
        private const val KEY_BUBBLE_POS_X = "bubble_pos_x"
        private const val KEY_BUBBLE_POS_Y = "bubble_pos_y"
        private const val KEY_DEBOUNCE_MS = "debounce_ms"

        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                val instance = AppPreferences(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
