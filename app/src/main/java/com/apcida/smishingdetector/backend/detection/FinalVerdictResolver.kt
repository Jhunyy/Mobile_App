package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

/** Combines independent rule evidence with an uncalibrated model opinion. */
object FinalVerdictResolver {
    data class Verdict(val riskLevel: RiskLevel, val isFlagged: Boolean)

    fun resolve(ruleRiskLevel: RiskLevel, gemmaResult: GemmaResult): Verdict =
        resolve(ruleRiskLevel, gemmaResult, false, false)

    fun resolve(
        ruleRiskLevel: RiskLevel,
        gemmaResult: GemmaResult,
        @Suppress("UNUSED_PARAMETER") isSavedContact: Boolean,
        hasStrongRuleSignal: Boolean,
        isLowEvidenceMessage: Boolean = false
    ): Verdict {
        // A model failure is an analysis state, not evidence of a scam.
        if (!gemmaResult.isSuccessful) return verdict(ruleRiskLevel)

        val confidence = gemmaResult.confidence?.trim()?.uppercase()
        val level = when (gemmaResult.classification) {
            Constants.GEMMA_SCAM -> when {
                ruleRiskLevel == RiskLevel.SCAM -> RiskLevel.SCAM
                isLowEvidenceMessage && ruleRiskLevel == RiskLevel.SAFE -> RiskLevel.SAFE
                hasStrongRuleSignal && confidence in setOf("HIGH", "MEDIUM") -> RiskLevel.SCAM
                else -> RiskLevel.SUSPICIOUS
            }
            Constants.GEMMA_LEGITIMATE -> when (ruleRiskLevel) {
                RiskLevel.SAFE -> RiskLevel.SAFE
                RiskLevel.SUSPICIOUS -> if (confidence == "HIGH" && !hasStrongRuleSignal) {
                    RiskLevel.SAFE
                } else RiskLevel.SUSPICIOUS
                RiskLevel.SCAM -> if (confidence == "HIGH" ||
                    (confidence == "MEDIUM" && !hasStrongRuleSignal)) {
                    RiskLevel.SUSPICIOUS
                } else RiskLevel.SCAM
            }
            else -> ruleRiskLevel
        }
        // Contact membership provides context but cannot authenticate the sender.
        return verdict(level)
    }

    private fun verdict(level: RiskLevel): Verdict =
        Verdict(level, isFlagged = level != RiskLevel.SAFE)
}
