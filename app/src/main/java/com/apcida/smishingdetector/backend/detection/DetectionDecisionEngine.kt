package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel

/** The final policy shared by live SMS processing and offline batch evaluation. */
object DetectionDecisionEngine {
    data class Outcome(
        val finalVerdict: RiskLevel,
        val points: CombinedRiskScorer.Breakdown
    )

    fun resolve(
        body: String,
        ruleScore: Float,
        ruleVerdict: RiskLevel,
        hasStrongRuleSignal: Boolean,
        senderPoints: Int,
        isSavedContact: Boolean,
        historyPoints: Int,
        gemmaResult: GemmaResult
    ): Outcome {
        val simpleGreeting = SimpleGreetingPolicy.isEligible(
            body, ruleScore, hasStrongRuleSignal, senderPoints
        )
        val lowEvidence = MessageSignals.isLowEvidenceConversation(
            body, ruleScore, hasStrongRuleSignal, senderPoints
        )
        val verdict = FinalVerdictResolver.resolve(
            ruleVerdict, gemmaResult, isSavedContact, hasStrongRuleSignal, lowEvidence
        )
        val points = CombinedRiskScorer.score(
            ruleScore = ruleScore,
            hasStrongRuleSignal = hasStrongRuleSignal,
            historyPoints = historyPoints,
            senderPoints = senderPoints,
            gemmaResult = gemmaResult.takeUnless { simpleGreeting },
            isLowEvidenceMessage = lowEvidence
        )
        val finalVerdict = if (simpleGreeting) SimpleGreetingPolicy.verdict(historyPoints)
        else CombinedRiskScorer.withSuspiciousFloor(verdict.riskLevel, points.total)
        return Outcome(finalVerdict, points)
    }
}
