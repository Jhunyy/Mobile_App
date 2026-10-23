package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedRiskScorerTest {
    @Test
    fun confirmedScamAndStrongRulesAreCappedAtOneHundred() {
        val result = CombinedRiskScorer.score(
            ruleScore = 90f,
            hasStrongRuleSignal = true,
            historyPoints = 25,
            senderPoints = 5,
            gemmaResult = GemmaResult("SCAM", "HIGH", "Credential request")
        )

        assertEquals(25, result.rulePoints)
        assertEquals(60, result.aiPoints)
        assertEquals(25, result.historyPoints)
        assertEquals(100, result.total)
    }

    @Test
    fun strongSignalDoesNotDoubleCountKeywordsAndLowConfidenceStaysBelowScamEvidence() {
        val result = CombinedRiskScorer.score(
            ruleScore = 35f,
            hasStrongRuleSignal = true,
            historyPoints = 0,
            senderPoints = 0,
            gemmaResult = GemmaResult("SCAM", "LOW", "Limited context")
        )

        assertEquals(25, result.rulePoints)
        assertEquals(30, result.aiPoints)
        assertEquals(55, result.total)
    }

    @Test
    fun noHistoryOrSignalsDoesNotCreateRiskPoints() {
        val result = CombinedRiskScorer.score(0f, false, 0, 0)
        assertEquals(0, result.total)
    }

    @Test
    fun twentyFivePointsWarnsWithoutPromotingSuspicionToScam() {
        assertEquals(RiskLevel.SAFE, CombinedRiskScorer.withSuspiciousFloor(RiskLevel.SAFE, 24))
        assertEquals(RiskLevel.SUSPICIOUS, CombinedRiskScorer.withSuspiciousFloor(RiskLevel.SAFE, 25))
        assertEquals(RiskLevel.SUSPICIOUS, CombinedRiskScorer.withSuspiciousFloor(RiskLevel.SUSPICIOUS, 90))
        assertEquals(RiskLevel.SCAM, CombinedRiskScorer.withSuspiciousFloor(RiskLevel.SCAM, 0))
    }

    @Test
    fun onlyRecentSuccessfulAiScamCarriesHistoryPoints() {
        val now = 200_000_000L
        val confirmed = Message(
            content = "Send your OTP",
            receivedAt = now - ConversationRiskPolicy.WARNING_WINDOW_MILLIS,
            aiAnalysisStatus = "AVAILABLE",
            gemmaClassification = "SCAM",
            gemmaConfidence = "MEDIUM",
            finalClassification = "SCAM"
        )
        assertEquals(25, ConversationRiskPolicy.historyPoints(listOf(confirmed), now))
        assertEquals(0, ConversationRiskPolicy.historyPoints(listOf(confirmed), now + 1))
        assertEquals(0, ConversationRiskPolicy.historyPoints(
            listOf(confirmed.copy(aiAnalysisStatus = "UNAVAILABLE")), now
        ))
        assertEquals(10, ConversationRiskPolicy.historyPoints(
            listOf(confirmed.copy(gemmaConfidence = "LOW", finalClassification = "SUSPICIOUS",
                aiRiskPoints = 30)), now
        ))
        assertEquals(0, ConversationRiskPolicy.historyPoints(
            listOf(confirmed.copy(gemmaConfidence = "LOW", finalClassification = "SAFE",
                aiRiskPoints = 0)), now
        ))
    }

    @Test
    fun absentContactIsNotRiskAndConcreteSenderMismatchIsSmall() {
        assertEquals(0, SenderSignals.points(null, "Hello"))
        assertEquals(0, SenderSignals.points("09171234567", "I went to BDO today"))
        assertEquals(5, SenderSignals.points("09171234567", "This is from BDO. Verify now."))
    }
}
