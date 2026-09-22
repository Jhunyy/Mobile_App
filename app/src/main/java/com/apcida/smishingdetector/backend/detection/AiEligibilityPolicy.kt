package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.RiskLevel

/** Keyword/rule risk is evidence for Gemma, never an invocation gate. */
object AiEligibilityPolicy {
    fun shouldQueue(@Suppress("UNUSED_PARAMETER") ruleRiskLevel: RiskLevel): Boolean = true
}
