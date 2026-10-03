package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

/** Combines the preliminary keyword score with contextual analysis. */
object DecisionEngine {
    fun decide(keywordRisk: RiskLevel, gemma: GemmaResult, messageBody: String): RiskLevel {
        if (!gemma.isSuccessful) return keywordRisk

        return when (gemma.classification) {
            Constants.GEMMA_SCAM -> when {
                keywordRisk == RiskLevel.SCAM || MessageSignals.hasHighRiskRequest(messageBody) -> RiskLevel.SCAM
                keywordRisk != RiskLevel.SAFE || MessageSignals.hasActionableSignal(messageBody) ->
                    if (gemma.confidence == Constants.CONFIDENCE_LOW) RiskLevel.SUSPICIOUS else RiskLevel.SCAM
                gemma.confidence == Constants.CONFIDENCE_LOW -> RiskLevel.SAFE
                else -> RiskLevel.SUSPICIOUS
            }
            Constants.GEMMA_LEGITIMATE -> if (keywordRisk == RiskLevel.SCAM) {
                RiskLevel.SUSPICIOUS
            } else {
                RiskLevel.SAFE
            }
            else -> when {
                keywordRisk != RiskLevel.SAFE -> keywordRisk
                MessageSignals.hasActionableSignal(messageBody) -> RiskLevel.SUSPICIOUS
                else -> RiskLevel.SAFE
            }
        }
    }
}
