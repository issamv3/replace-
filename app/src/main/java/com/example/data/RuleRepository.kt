package com.example.data

import kotlinx.coroutines.flow.Flow

class RuleRepository(
    private val ruleDao: ReplacementRuleDao,
    val preferences: AppPreferences
) {
    val allRules: Flow<List<ReplacementRule>> = ruleDao.getAllRules()
    val activeRules: Flow<List<ReplacementRule>> = ruleDao.getActiveRules()

    suspend fun getActiveRulesList(): List<ReplacementRule> = ruleDao.getActiveRulesList()

    suspend fun insertRule(rule: ReplacementRule): Long = ruleDao.insertRule(rule)

    suspend fun updateRule(rule: ReplacementRule) = ruleDao.updateRule(rule)

    suspend fun deleteRule(rule: ReplacementRule) = ruleDao.deleteRule(rule)

    suspend fun deleteRuleById(id: Long) = ruleDao.deleteRuleById(id)

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = ruleDao.setRuleEnabled(id, enabled)

    suspend fun clearAll() = ruleDao.clearAllRules()
}
