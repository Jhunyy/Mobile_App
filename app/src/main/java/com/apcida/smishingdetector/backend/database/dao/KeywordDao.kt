package com.apcida.smishingdetector.backend.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.apcida.smishingdetector.model.entity.Keyword

@Dao
interface KeywordDao {

    // Insert a single keyword
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeyword(keyword: Keyword): Long

    // Insert multiple keywords at once (used by DatabaseSeeder)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllKeywords(keywords: List<Keyword>)

    // Update a keyword (weight adjustment, activation toggle)
    @Update
    suspend fun updateKeyword(keyword: Keyword)

    // Get all active keywords used by KeywordEngine
    @Query("SELECT * FROM keywords WHERE is_active = 1")
    suspend fun getAllActiveKeywords(): List<Keyword>

    // Get keywords filtered by language
    @Query("SELECT * FROM keywords WHERE language = :language AND is_active = 1")
    suspend fun getKeywordsByLanguage(language: String): List<Keyword>

    // Get keywords filtered by pattern type
    @Query("SELECT * FROM keywords WHERE pattern_type = :type AND is_active = 1")
    suspend fun getKeywordsByType(type: String): List<Keyword>

    // Get total count of keywords in database
    @Query("SELECT COUNT(*) FROM keywords")
    suspend fun getKeywordCount(): Int

    // Deactivate a keyword without deleting it
    @Query("UPDATE keywords SET is_active = 0 WHERE keyword_id = :keywordId")
    suspend fun deactivateKeyword(keywordId: Long)

    // Delete all keywords — used during re-seeding
    @Query("DELETE FROM keywords")
    suspend fun deleteAllKeywords()
}