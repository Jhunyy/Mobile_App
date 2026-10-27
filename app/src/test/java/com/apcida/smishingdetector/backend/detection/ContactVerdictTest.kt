package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Keyword
import com.apcida.smishingdetector.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactVerdictTest {
    @Test
    fun savedContactDoesNotAuthenticateSender() {
        assertVerdict(RiskLevel.SAFE, false, RiskLevel.SUSPICIOUS, legitimate("HIGH"), true)
        assertVerdict(RiskLevel.SAFE, false, RiskLevel.SUSPICIOUS, legitimate("HIGH"), false)
        assertVerdict(RiskLevel.SUSPICIOUS, true, RiskLevel.SUSPICIOUS, legitimate("MEDIUM"), true)
        assertVerdict(RiskLevel.SUSPICIOUS, true, RiskLevel.SUSPICIOUS, legitimate("LOW"), true)
        assertVerdict(RiskLevel.SUSPICIOUS, true, RiskLevel.SUSPICIOUS, GemmaResult.fallback(), true)
    }

    @Test
    fun strongSignalsAndScamRuleResultsStayFlagged() {
        val strongSignal = FinalVerdictResolver.resolve(
            RiskLevel.SUSPICIOUS, legitimate("HIGH"),
            isSavedContact = true, hasStrongRuleSignal = true
        )
        assertEquals(RiskLevel.SUSPICIOUS, strongSignal.riskLevel)
        assertTrue(strongSignal.isFlagged)

        assertVerdict(RiskLevel.SUSPICIOUS, true, RiskLevel.SCAM, legitimate("HIGH"), true)
        assertVerdict(RiskLevel.SUSPICIOUS, true, RiskLevel.SUSPICIOUS,
            GemmaResult(Constants.GEMMA_SCAM, "HIGH", "Scam evidence."), true)
    }

    @Test
    fun credentialRequestsAndHighWeightUrlsBlockContactClearance() {
        assertTrue(MessageSignals.hasStrongRuleSignal("Send your OTP now", emptyList()))
        assertTrue(MessageSignals.hasStrongRuleSignal("Open this link", listOf(
            Keyword(pattern = "bdo-verify", patternType = "URL_PATTERN", weight = 40f)
        )))
        assertFalse(MessageSignals.hasStrongRuleSignal("Open this link", listOf(
            Keyword(pattern = "bit.ly", patternType = "URL_PATTERN", weight = 35f)
        )))
        assertFalse(MessageSignals.hasStrongRuleSignal("Your appointment is tomorrow", listOf(
            Keyword(pattern = "URL:example.com", patternType = "URL_PATTERN", weight = 10f)
        )))
    }

    private fun assertVerdict(
        expectedLevel: RiskLevel,
        expectedFlagged: Boolean,
        ruleLevel: RiskLevel,
        ai: GemmaResult,
        isSavedContact: Boolean
    ) {
        val verdict = FinalVerdictResolver.resolve(
            ruleLevel, ai, isSavedContact, hasStrongRuleSignal = false
        )
        assertEquals(expectedLevel, verdict.riskLevel)
        assertEquals(expectedFlagged, verdict.isFlagged)
    }

    private fun legitimate(confidence: String) =
        GemmaResult(Constants.GEMMA_LEGITIMATE, confidence, "Legitimate in context.")
}
