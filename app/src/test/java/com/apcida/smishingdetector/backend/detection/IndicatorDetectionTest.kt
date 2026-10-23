package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorDetectionTest {

    @Test
    fun keywordMatchingUsesWordBoundaries() {
        assertTrue(KeywordPatternMatcher.matches("Send your PIN now", "pin", "KEYWORD"))
        assertFalse(KeywordPatternMatcher.matches("Your shopping list", "pin", "KEYWORD"))
    }

    @Test
    fun urlDetectorFindsOrdinaryAndSuspiciousUrls() {
        val ordinary = UrlIndicatorDetector.detect("Visit https://example.com/help", emptyList())
        val suspicious = UrlIndicatorDetector.detect("Visit http://secure-login-update.example/path", emptyList())

        assertTrue(ordinary.single().weight > 0f)
        assertTrue(suspicious.single().weight > ordinary.single().weight)
    }

    @Test
    fun urlPatternsOnlyMatchExtractedHosts() {
        assertTrue(KeywordPatternMatcher.matches("Open bit.ly/abc", "bit.ly", "URL_PATTERN"))
        assertTrue(KeywordPatternMatcher.matches("Open https://parcel.xyz/pay", ".xyz", "URL_PATTERN"))
        assertTrue(KeywordPatternMatcher.matches("Open www.gcash-promo.com", "gcash-promo", "URL_PATTERN"))
        assertFalse(KeywordPatternMatcher.matches("Email help@bit.ly", "bit.ly", "URL_PATTERN"))
        assertFalse(KeywordPatternMatcher.matches("Open https://notbit.ly.example/pay", "bit.ly", "URL_PATTERN"))
        assertFalse(KeywordPatternMatcher.matches("Open https://example.com/bit.ly", "bit.ly", "URL_PATTERN"))
        assertFalse(KeywordPatternMatcher.matches("Open https://example.com/parcel.xyz", ".xyz", "URL_PATTERN"))
    }

    @Test
    fun genericUrlEvidenceDoesNotDoubleCountSeededHost() {
        val seeded = Keyword(
            pattern = "bit.ly", patternType = "URL_PATTERN", weight = 35f
        )
        val matches = UrlIndicatorDetector.detect(
            "Open bit.ly/abc and https://example.com/help", listOf(seeded)
        )

        assertEquals(listOf("URL:example.com"), matches.map { it.pattern })
    }
}
