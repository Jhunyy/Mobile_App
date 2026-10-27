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
     * Phrase patterns are checked in the message body; URL patterns
     * are checked only against hosts extracted from links or bare domains.
     */
    suspend fun analyze(messageBody: String): List<Keyword> {
        Log.d(TAG, "Analyzing normalized SMS content.")

        // Load all active keywords from database
        val allKeywords = keywordDao.getAllActiveKeywords()
        Log.d(TAG, "Loaded ${allKeywords.size} active keywords from database.")
        val urls = UrlCandidates.extract(messageBody)

        val matched = allKeywords.filterTo(mutableListOf()) { keyword ->
            keyword.patternType != "URL_PATTERN" && RuleWeightPolicy.weight(keyword) > 0f &&
                KeywordPatternMatcher.matches(messageBody, keyword.pattern, keyword.patternType)
        }

        // A host can match both a specific spoof label and a generic suffix.
        // Count only the strongest seeded URL clue for that host.
        val urlPatterns = allKeywords.filter { it.patternType == "URL_PATTERN" &&
            RuleWeightPolicy.weight(it) > 0f }
        urls.forEach { url ->
            val strongest = urlPatterns.filter { UrlCandidates.matchesHost(url.host, it.pattern) }
                .maxByOrNull { RuleWeightPolicy.weight(it) }
            if (strongest != null && matched.none { it.pattern == strongest.pattern &&
                it.patternType == strongest.patternType }) matched.add(strongest)
        }

        matched.addAll(UrlIndicatorDetector.detect(urls, matched))

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
