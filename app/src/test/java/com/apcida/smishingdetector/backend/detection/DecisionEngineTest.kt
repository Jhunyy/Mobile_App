package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionEngineTest {
    @Test
    fun scamCanBeDetectedWithoutKeywordMatches() {
        assertEquals(RiskLevel.SCAM, DecisionEngine.decide(RiskLevel.SAFE, result(Constants.GEMMA_SCAM)))
    }

    @Test
    fun conflictingHighKeywordRiskRemainsSuspicious() {
        assertEquals(RiskLevel.SUSPICIOUS, DecisionEngine.decide(RiskLevel.SCAM, result(Constants.GEMMA_LEGITIMATE)))
    }

    @Test
    fun uncertaintyAndFailureKeepRiskVisible() {
        assertEquals(RiskLevel.SUSPICIOUS, DecisionEngine.decide(RiskLevel.SAFE, result(Constants.GEMMA_UNCERTAIN)))
        assertEquals(RiskLevel.SCAM, DecisionEngine.decide(RiskLevel.SCAM, GemmaResult.fallback()))
    }

    private fun result(classification: String) = GemmaResult(
        classification = classification,
        confidence = Constants.CONFIDENCE_LOW,
        rationale = "Observed evidence in message."
    )
}
