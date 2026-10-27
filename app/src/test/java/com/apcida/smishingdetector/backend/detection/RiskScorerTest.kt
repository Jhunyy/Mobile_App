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

        assertEquals(23f, scorer.compute(matches), 0.001f)
    }

    @Test
    fun compute_capsKeywordAndUrlContributionsSeparately() {
        val matches = listOf(
            keyword("claim your prize", "KEYWORD", 40f),
            keyword("send your otp", "KEYWORD", 40f),
            keyword("account will be locked", "KEYWORD", 40f),
            keyword("brand-verify", "URL_PATTERN", 50f),
            keyword("fake-login", "URL_PATTERN", 50f),
            keyword("account-update", "URL_PATTERN", 50f)
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

        assertEquals(16f, breakdown.keywordScore, 0.001f)
        assertEquals(30f, breakdown.urlScore, 0.001f)
        assertEquals(46f, breakdown.totalScore, 0.001f)
        assertEquals(2, breakdown.keywordCount)
        assertEquals(1, breakdown.urlCount)
    }

    @Test
    fun overlappingPhrasesCountOnlyTheMoreSpecificClue() {
        val matches = listOf(
            keyword("claim", "KEYWORD", 25f),
            keyword("claim your prize", "KEYWORD", 30f)
        )
        assertEquals(20f, scorer.compute(matches), 0.001f)
    }

    @Test
    fun legacyBroadPatternsAndUrlSuffixesRemainLowWeight() {
        val matches = listOf(
            keyword("gcash", "KEYWORD", 15f),
            keyword("your package", "KEYWORD", 20f),
            keyword("reward", "KEYWORD", 25f),
            keyword(".online", "URL_PATTERN", 25f)
        )
        assertEquals(15f, scorer.compute(matches), 0.001f)
    }

    private fun keyword(pattern: String, patternType: String, weight: Float): Keyword {
        return Keyword(
            pattern = pattern,
            patternType = patternType,
            weight = weight
        )
    }
}
