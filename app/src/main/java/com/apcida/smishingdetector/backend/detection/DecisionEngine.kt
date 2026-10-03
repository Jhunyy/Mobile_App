package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

/** Combines the preliminary keyword score with contextual analysis. */
object DecisionEngine {
    fun decide(keywordRisk: RiskLevel, gemma: GemmaResult): RiskLevel {
        if (!gemma.isSuccessful) return keywordRisk

        return when (gemma.classification) {
            Constants.GEMMA_SCAM -> RiskLevel.SCAM
            Constants.GEMMA_LEGITIMATE -> if (keywordRisk == RiskLevel.SCAM) {
                RiskLevel.SUSPICIOUS
            } else {
                RiskLevel.SAFE
            }
            else -> if (keywordRisk == RiskLevel.SAFE) RiskLevel.SUSPICIOUS else keywordRisk
        }
    }
}
