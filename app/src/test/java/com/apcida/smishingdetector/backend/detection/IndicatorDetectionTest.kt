package com.apcida.smishingdetector.backend.detection

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
}
