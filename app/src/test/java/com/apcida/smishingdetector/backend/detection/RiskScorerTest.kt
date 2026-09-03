package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword
import org.junit.Assert.assertEquals
import org.junit.Test

class RiskScorerTest {

    private val scorer = RiskScorer()

    @Test
    fun compute_returnsZeroWhenNoKeywordsMatched() {
        assertEquals(0f, scorer.compute(emptyList()), 0.001f)
    }

    @Test
    fun compute_sumsKeywordAndUrlWeights() {
        val matches = listOf(
            keyword("urgent", "KEYWORD", 25f),
            keyword("bit.ly", "URL_PATTERN", 35f)
        )

        assertEquals(60f, scorer.compute(matches), 0.001f)
    }

    @Test
    fun compute_capsKeywordAndUrlContributionsSeparately() {
        val matches = listOf(
            keyword("claim", "KEYWORD", 40f),
            keyword("reward", "KEYWORD", 40f),
            keyword("short-url", "URL_PATTERN", 50f),
            keyword("suspicious-domain", "URL_PATTERN", 50f)
        )

        assertEquals(140f, scorer.compute(matches), 0.001f)
    }

    @Test
    fun getScoreBreakdown_returnsCountsAndCappedScores() {
        val matches = listOf(
            keyword("verify", "KEYWORD", 35f),
            keyword("account", "KEYWORD", 35f),
            keyword("link", "URL_PATTERN", 90f)
        )

        val breakdown = scorer.getScoreBreakdown(matches)

        assertEquals(60f, breakdown.keywordScore, 0.001f)
        assertEquals(80f, breakdown.urlScore, 0.001f)
        assertEquals(140f, breakdown.totalScore, 0.001f)
        assertEquals(2, breakdown.keywordCount)
        assertEquals(1, breakdown.urlCount)
    }

    private fun keyword(pattern: String, patternType: String, weight: Float): Keyword {
        return Keyword(
            pattern = pattern,
            patternType = patternType,
            weight = weight
        )
    }
}
