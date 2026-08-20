package com.apcida.smishingdetector.backend.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.apcida.smishingdetector.model.entity.SafetyTip
import kotlinx.coroutines.flow.Flow

@Dao
interface SafetyTipDao {

    // Insert a single tip
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTip(tip: SafetyTip): Long

    // Insert multiple tips at once (used by DatabaseSeeder)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllTips(tips: List<SafetyTip>)

    // Update an existing tip
    @Update
    suspend fun updateTip(tip: SafetyTip)

    // Get all active tips — returns Flow for live UI
    @Query("SELECT * FROM safety_tips WHERE is_active = 1 ORDER BY tip_id ASC")
    fun getAllActiveTips(): Flow<List<SafetyTip>>

    // Get tips filtered by category
    @Query("SELECT * FROM safety_tips WHERE category = :category AND is_active = 1")
    fun getTipsByCategory(category: String): Flow<List<SafetyTip>>

    // Get total count of tips
    @Query("SELECT COUNT(*) FROM safety_tips")
    suspend fun getTipCount(): Int

    // Delete all tips — used before refreshing from server
    @Query("DELETE FROM safety_tips")
    suspend fun deleteAllTips()
}