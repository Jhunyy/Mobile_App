package com.apcida.smishingdetector.backend.detection

import android.util.Log
import com.apcida.smishingdetector.model.entity.Keyword

class RiskScorer {

    companion object {
        private const val TAG = "RiskScorer"

        // Score caps to prevent extreme values from
        // a single category dominating the total score
        private const val MAX_KEYWORD_CONTRIBUTION = 60f
        private const val MAX_URL_CONTRIBUTION = 80f
    }

    /**
     * Computes the total weighted risk score from
     * a list of matched keywords.
     *
     * Keywords and URL patterns are scored separately
     * with individual caps, then combined into a
     * final total score.
     */
    fun compute(matchedKeywords: List<Keyword>): Float {
        if (matchedKeywords.isEmpty()) {
            Log.d(TAG, "No keywords matched. Risk score: 0.0")
            return 0f
        }

        // Separate keyword matches from URL pattern matches
        val keywordMatches = matchedKeywords.filter {
            it.patternType == "KEYWORD"
        }
        val urlMatches = matchedKeywords.filter {
            it.patternType == "URL_PATTERN"
        }

        // Sum keyword weights with cap
        val keywordScore = keywordMatches
            .sumOf { it.weight.toDouble() }
            .toFloat()
            .coerceAtMost(MAX_KEYWORD_CONTRIBUTION)

        // Sum URL pattern weights with cap
        val urlScore = urlMatches
            .sumOf { it.weight.toDouble() }
            .toFloat()
            .coerceAtMost(MAX_URL_CONTRIBUTION)

        val totalScore = keywordScore + urlScore

        Log.d(TAG, "Keyword score: $keywordScore | URL score: $urlScore | Total: $totalScore")

        return totalScore
    }

    /**
     * Returns a breakdown of the score by category.
     * Useful for displaying score details in the alert UI.
     */
    fun getScoreBreakdown(matchedKeywords: List<Keyword>): ScoreBreakdown {
        val keywordMatches = matchedKeywords.filter { it.patternType == "KEYWORD" }
        val urlMatches = matchedKeywords.filter { it.patternType == "URL_PATTERN" }

        val keywordScore = keywordMatches
            .sumOf { it.weight.toDouble() }
            .toFloat()
            .coerceAtMost(MAX_KEYWORD_CONTRIBUTION)

        val urlScore = urlMatches
            .sumOf { it.weight.toDouble() }
            .toFloat()
            .coerceAtMost(MAX_URL_CONTRIBUTION)

        return ScoreBreakdown(
            keywordScore = keywordScore,
            urlScore = urlScore,
            totalScore = keywordScore + urlScore,
            keywordCount = keywordMatches.size,
            urlCount = urlMatches.size
        )
    }

    /**
     * Data class holding the score breakdown by category.
     */
    data class ScoreBreakdown(
        val keywordScore: Float,
        val urlScore: Float,
        val totalScore: Float,
        val keywordCount: Int,
        val urlCount: Int
    )
}