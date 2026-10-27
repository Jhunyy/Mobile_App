package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

/** Explainable points, not a probability. The original rule score remains separate. */
object CombinedRiskScorer {
    const val SUSPICIOUS_THRESHOLD = 25

    data class Breakdown(
        val rulePoints: Int,
        val aiPoints: Int,
        val historyPoints: Int,
        val senderPoints: Int
    ) {
        val total: Int get() = (rulePoints + aiPoints + historyPoints + senderPoints).coerceAtMost(100)
    }

    fun score(
        ruleScore: Float,
        hasStrongRuleSignal: Boolean,
        historyPoints: Int,
        senderPoints: Int,
        gemmaResult: GemmaResult? = null,
        isLowEvidenceMessage: Boolean = false
    ): Breakdown {
        val keywordPoints = when {
            ruleScore >= 60f -> 25
            ruleScore >= 30f -> 10
            ruleScore > 0f -> 5
            else -> 0
        }
        val aiPoints = when {
            isLowEvidenceMessage || gemmaResult?.isSuccessful != true ||
                gemmaResult.classification != Constants.GEMMA_SCAM -> 0
            gemmaResult.confidence in setOf(Constants.CONFIDENCE_HIGH, Constants.CONFIDENCE_MEDIUM) -> 60
            gemmaResult.confidence == Constants.CONFIDENCE_LOW -> 30
            else -> 0
        }
        return Breakdown(
            rulePoints = maxOf(keywordPoints, if (hasStrongRuleSignal) 25 else 0),
            aiPoints = aiPoints,
            historyPoints = historyPoints.coerceIn(0, 25),
            senderPoints = senderPoints.coerceIn(0, 5)
        )
    }

    fun withSuspiciousFloor(base: RiskLevel, points: Int): RiskLevel =
        if (base == RiskLevel.SAFE && points >= SUSPICIOUS_THRESHOLD) RiskLevel.SUSPICIOUS else base
}
