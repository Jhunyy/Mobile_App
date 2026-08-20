package com.apcida.smishingdetector.backend.repository

import com.apcida.smishingdetector.backend.database.dao.KeywordDao
import com.apcida.smishingdetector.model.entity.Keyword

class KeywordRepository(private val keywordDao: KeywordDao) {

    /**
     * Inserts a single keyword into the database.
     * Returns the generated keyword ID.
     */
    suspend fun insertKeyword(keyword: Keyword): Long {
        return keywordDao.insertKeyword(keyword)
    }

    /**
     * Inserts multiple keywords at once.
     * Used by DatabaseSeeder on first launch.
     */
    suspend fun insertAllKeywords(keywords: List<Keyword>) {
        keywordDao.insertAllKeywords(keywords)
    }

    /**
     * Updates an existing keyword.
     */
    suspend fun updateKeyword(keyword: Keyword) {
        keywordDao.updateKeyword(keyword)
    }

    /**
     * Returns all active keywords.
     * Used by KeywordEngine during Stage 1 detection.
     */
    suspend fun getAllActiveKeywords(): List<Keyword> {
        return keywordDao.getAllActiveKeywords()
    }

    /**
     * Returns keywords filtered by language.
     */
    suspend fun getKeywordsByLanguage(language: String): List<Keyword> {
        return keywordDao.getKeywordsByLanguage(language)
    }

    /**
     * Returns keywords filtered by pattern type.
     */
    suspend fun getKeywordsByType(type: String): List<Keyword> {
        return keywordDao.getKeywordsByType(type)
    }

    /**
     * Returns the total count of keywords in the database.
     */
    suspend fun getKeywordCount(): Int {
        return keywordDao.getKeywordCount()
    }

    /**
     * Deactivates a keyword without deleting it.
     */
    suspend fun deactivateKeyword(keywordId: Long) {
        keywordDao.deactivateKeyword(keywordId)
    }

    /**
     * Deletes all keywords.
     * Used before re-seeding the database.
     */
    suspend fun deleteAllKeywords() {
        keywordDao.deleteAllKeywords()
    }
}