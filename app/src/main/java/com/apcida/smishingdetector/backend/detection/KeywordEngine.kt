package com.apcida.smishingdetector.backend.detection

import android.util.Log
import com.apcida.smishingdetector.backend.database.dao.KeywordDao
import com.apcida.smishingdetector.model.entity.Keyword

class KeywordEngine(private val keywordDao: KeywordDao) {

    companion object {
        private const val TAG = "KeywordEngine"
    }

    /**
     * Analyzes the message body against all active keywords
     * in the database and returns a list of matched keywords.
     *
     * Matching is case-insensitive and checks if the pattern
     * exists anywhere within the message body.
     */
    suspend fun analyze(messageBody: String): List<Keyword> {
        Log.d(TAG, "Analyzing normalized SMS content.")

        // Load all active keywords from database
        val allKeywords = keywordDao.getAllActiveKeywords()
        Log.d(TAG, "Loaded ${allKeywords.size} active keywords from database.")

        // Find all keywords that appear in the message
        val matched = mutableListOf<Keyword>()

        for (keyword in allKeywords) {
            if (KeywordPatternMatcher.matches(messageBody, keyword.pattern, keyword.patternType)) {
                matched.add(keyword)
            }
        }

        matched.addAll(UrlIndicatorDetector.detect(messageBody, matched))

        Log.d(TAG, "Total matches found: ${matched.size}")
        return matched
    }

    /**
     * Analyzes the message and returns only URL-type matches.
     * Used for URL-specific reporting and display.
     */
    suspend fun analyzeUrls(messageBody: String): List<Keyword> {
        return analyze(messageBody).filter {
            it.patternType == "URL_PATTERN"
        }
    }

    /**
     * Analyzes the message and returns only keyword-type matches.
     * Used for keyword-specific reporting and display.
     */
    suspend fun analyzeKeywordsOnly(messageBody: String): List<Keyword> {
        return analyze(messageBody).filter {
            it.patternType == "KEYWORD"
        }
    }
}
