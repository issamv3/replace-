package com.example.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppPreferences
import com.example.data.ReplacementRule
import com.example.data.RuleRepository
import com.example.service.TextReplacerAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val preferences = AppPreferences.getInstance(application)
    private val repository = RuleRepository(database.replacementRuleDao(), preferences)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _hasOverlayPermission = MutableStateFlow(checkOverlayPermission(application))
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    val isServiceActive: StateFlow<Boolean> = TextReplacerAccessibilityService.isServiceActive

    val bubbleOpacity: StateFlow<Float> = preferences.bubbleOpacity
    val bubbleSizeDp: StateFlow<Int> = preferences.bubbleSizeDp
    val ocrFallbackEnabled: StateFlow<Boolean> = preferences.ocrFallbackEnabled
    val isAutoApplyActive: StateFlow<Boolean> = preferences.isAutoApplyActive
    val debounceMs: StateFlow<Long> = preferences.debounceMs
    val isBubbleHidden: StateFlow<Boolean> = preferences.isBubbleHidden
    val hotspotPosition: StateFlow<String> = preferences.hotspotPosition

    val filteredRules: StateFlow<List<ReplacementRule>> = combine(
        repository.allRules,
        _searchQuery
    ) { rules, query ->
        if (query.isBlank()) {
            rules
        } else {
            rules.filter { rule ->
                rule.originalText.contains(query, ignoreCase = true) ||
                        rule.replacementText.contains(query, ignoreCase = true) ||
                        (rule.packageName?.contains(query, ignoreCase = true) == true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun refreshPermissions() {
        _hasOverlayPermission.value = checkOverlayPermission(getApplication())
    }

    fun saveRule(rule: ReplacementRule) {
        viewModelScope.launch {
            if (rule.id == 0L) {
                repository.insertRule(rule)
            } else {
                repository.updateRule(rule)
            }
        }
    }

    fun toggleRule(rule: ReplacementRule) {
        viewModelScope.launch {
            repository.setRuleEnabled(rule.id, !rule.isEnabled)
        }
    }

    fun deleteRule(rule: ReplacementRule) {
        viewModelScope.launch {
            repository.deleteRule(rule)
        }
    }

    fun clearAllRules() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun setBubbleOpacity(opacity: Float) {
        preferences.setBubbleOpacity(opacity)
    }

    fun setBubbleSize(sizeDp: Int) {
        preferences.setBubbleSizeDp(sizeDp)
    }

    fun setOcrFallback(enabled: Boolean) {
        preferences.setOcrFallbackEnabled(enabled)
    }

    fun setDebounceMs(ms: Long) {
        preferences.setDebounceMs(ms)
    }

    fun setHotspotPosition(pos: String) {
        preferences.setHotspotPosition(pos)
    }

    fun restoreFloatingBubble() {
        preferences.setBubbleHidden(false)
        TextReplacerAccessibilityService.restoreBubble()
    }

    fun seedDemoRules() {
        viewModelScope.launch {
            val samples = listOf(
                ReplacementRule(
                    originalText = "2026-10-23 09:45",
                    replacementText = "2026-10-25 18:30",
                    textColor = 0xFF38BDF8.toInt(), // Sky Blue
                    backgroundColor = 0xFF0F172A.toInt(), // Dark Slate
                    matchMode = ReplacementRule.MATCH_CONTAINS,
                    fontWeight = 700,
                    isBold = true
                ),
                ReplacementRule(
                    originalText = "$1,250.00",
                    replacementText = "$1,000,000.00",
                    textColor = 0xFF10B981.toInt(), // Emerald Green
                    backgroundColor = 0xFF0F172A.toInt(), // Dark Slate
                    matchMode = ReplacementRule.MATCH_EXACT,
                    fontWeight = 700,
                    isBold = true
                ),
                ReplacementRule(
                    originalText = "Standard Member",
                    replacementText = "VIP Elite Investor",
                    textColor = 0xFFF59E0B.toInt(), // Amber Gold
                    backgroundColor = 0xFF1E293B.toInt(),
                    matchMode = ReplacementRule.MATCH_CONTAINS,
                    fontWeight = 700,
                    isBold = true
                )
            )
            for (rule in samples) {
                repository.insertRule(rule)
            }
        }
    }

    private fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }
}
