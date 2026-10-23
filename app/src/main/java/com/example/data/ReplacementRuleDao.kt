package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplacementRuleDao {
    @Query("SELECT * FROM replacement_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<ReplacementRule>>

    @Query("SELECT * FROM replacement_rules WHERE isEnabled = 1 ORDER BY createdAt DESC")
    fun getActiveRules(): Flow<List<ReplacementRule>>

    @Query("SELECT * FROM replacement_rules WHERE isEnabled = 1")
    suspend fun getActiveRulesList(): List<ReplacementRule>

    @Query("SELECT * FROM replacement_rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: Long): ReplacementRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: ReplacementRule): Long

    @Update
    suspend fun updateRule(rule: ReplacementRule)

    @Delete
    suspend fun deleteRule(rule: ReplacementRule)

    @Query("DELETE FROM replacement_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("UPDATE replacement_rules SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, isEnabled: Boolean)

    @Query("DELETE FROM replacement_rules")
    suspend fun clearAllRules()
}
