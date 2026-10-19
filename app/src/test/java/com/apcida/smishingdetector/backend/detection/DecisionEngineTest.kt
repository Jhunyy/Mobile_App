package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionEngineTest {
    @Test
    fun scamCanBeDetectedWithoutKeywordMatches() {
        assertEquals(RiskLevel.SCAM, DecisionEngine.decide(RiskLevel.SAFE, result(Constants.GEMMA_SCAM), "Please send cash now"))
    }

    @Test
    fun ordinaryMeetingReminderIsNotScamDespiteLowConfidenceModelFalsePositive() {
        val reminder = "Hi! Just reminding you that our meeting is tomorrow at 10 AM. See you then."
        assertEquals(RiskLevel.SAFE, DecisionEngine.decide(RiskLevel.SAFE, result(Constants.GEMMA_SCAM), reminder))
    }

    @Test
    fun unsupportedModelScamVerdictWithMoreConfidenceNeedsReview() {
        val model = result(Constants.GEMMA_SCAM).copy(confidence = Constants.CONFIDENCE_HIGH)
        assertEquals(RiskLevel.SUSPICIOUS, DecisionEngine.decide(RiskLevel.SAFE, model, "Meeting tomorrow at 10 AM"))
    }

    @Test
    fun aLinkAloneDoesNotTurnLowConfidenceVerdictIntoScam() {
        val reminder = "Meeting tomorrow at 10 AM. Join at https://meet.example.com/room"
        assertEquals(RiskLevel.SUSPICIOUS, DecisionEngine.decide(RiskLevel.SAFE, result(Constants.GEMMA_SCAM), reminder))
    }

    @Test
    fun conflictingHighKeywordRiskRemainsSuspicious() {
        assertEquals(RiskLevel.SUSPICIOUS, DecisionEngine.decide(RiskLevel.SCAM, result(Constants.GEMMA_LEGITIMATE), "Routine account update"))
    }

    @Test
    fun uncertaintyAndFailureKeepRiskVisible() {
        assertEquals(RiskLevel.SUSPICIOUS, DecisionEngine.decide(RiskLevel.SAFE, result(Constants.GEMMA_UNCERTAIN), "Please reply with details"))
        assertEquals(RiskLevel.SCAM, DecisionEngine.decide(RiskLevel.SCAM, GemmaResult.fallback(), "Unknown message"))
    }

    private fun result(classification: String) = GemmaResult(
        classification = classification,
        confidence = Constants.CONFIDENCE_LOW,
        rationale = "Observed evidence in message."
    )
}
