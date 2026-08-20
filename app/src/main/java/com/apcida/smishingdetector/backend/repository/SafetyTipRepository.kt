package com.apcida.smishingdetector.backend.repository

import com.apcida.smishingdetector.backend.database.dao.SafetyTipDao
import com.apcida.smishingdetector.model.entity.SafetyTip
import kotlinx.coroutines.flow.Flow

class SafetyTipRepository(private val safetyTipDao: SafetyTipDao) {

    /**
     * Inserts a single safety tip.
     * Returns the generated tip ID.
     */
    suspend fun insertTip(tip: SafetyTip): Long {
        return safetyTipDao.insertTip(tip)
    }

    /**
     * Inserts multiple safety tips at once.
     * Used by DatabaseSeeder on first launch.
     */
    suspend fun insertAllTips(tips: List<SafetyTip>) {
        safetyTipDao.insertAllTips(tips)
    }

    /**
     * Updates an existing safety tip.
     */
    suspend fun updateTip(tip: SafetyTip) {
        safetyTipDao.updateTip(tip)
    }

    /**
     * Returns a Flow of all active safety tips.
     * Flow updates the UI automatically when data changes.
     */
    fun getAllActiveTips(): Flow<List<SafetyTip>> {
        return safetyTipDao.getAllActiveTips()
    }

    /**
     * Returns a Flow of tips filtered by category.
     */
    fun getTipsByCategory(category: String): Flow<List<SafetyTip>> {
        return safetyTipDao.getTipsByCategory(category)
    }

    /**
     * Returns the total count of safety tips in the database.
     */
    suspend fun getTipCount(): Int {
        return safetyTipDao.getTipCount()
    }

    /**
     * Deletes all safety tips.
     * Used before refreshing tips from the server.
     */
    suspend fun deleteAllTips() {
        safetyTipDao.deleteAllTips()
    }
}