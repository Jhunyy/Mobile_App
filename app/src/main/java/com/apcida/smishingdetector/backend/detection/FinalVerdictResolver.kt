package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

/**
 * Combines independent rule-based and contextual signals.
 *
 * A strong result from either detector is never silently discarded. In
 * particular, unavailable or uncertain AI analysis cannot turn a message into
 * SAFE, and a LEGITIMATE AI result cannot fully clear a rule-flagged message.
 */
object FinalVerdictResolver {

    data class Verdict(
        val riskLevel: RiskLevel,
        val isFlagged: Boolean
    )

    fun resolve(ruleRiskLevel: RiskLevel, gemmaResult: GemmaResult): Verdict {
        if (gemmaResult.isSuccessful &&
            gemmaResult.classification == Constants.GEMMA_SCAM
        ) {
            return Verdict(RiskLevel.SCAM, isFlagged = true)
        }

        if (gemmaResult.isSuccessful &&
            gemmaResult.classification == Constants.GEMMA_LEGITIMATE
        ) {
            return if (ruleRiskLevel == RiskLevel.SAFE) {
                Verdict(RiskLevel.SAFE, isFlagged = false)
            } else {
                Verdict(RiskLevel.SUSPICIOUS, isFlagged = true)
            }
        }

        return when (ruleRiskLevel) {
            RiskLevel.SAFE -> Verdict(RiskLevel.SUSPICIOUS, isFlagged = true)
            RiskLevel.SUSPICIOUS -> Verdict(RiskLevel.SUSPICIOUS, isFlagged = true)
            RiskLevel.SCAM -> Verdict(RiskLevel.SCAM, isFlagged = true)
        }
    }
}
