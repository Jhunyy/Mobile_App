package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnFusionPolicyTest {

    @Test
    fun everyRuleResult_isEligibleForAiAnalysis() {
        RiskLevel.entries.forEach { assertTrue(AiEligibilityPolicy.shouldQueue(it)) }
    }

    @Test
    fun ruleOnlySafeMessage_isSafeEvidenceButStillEligibleForAi() {
        val ruleResult = ThresholdEvaluator().evaluate(0f)
        assertEquals(RiskLevel.SAFE, ruleResult)
        assertTrue(AiEligibilityPolicy.shouldQueue(ruleResult))
    }

    @Test
    fun ruleSafeAndAiLegitimate_isSafe() {
        assertVerdict(RiskLevel.SAFE, legitimate(), RiskLevel.SAFE, false)
    }

    @Test
    fun ruleSafeAndAiScam_requiresIndependentEvidenceForDefinitiveScam() {
        assertVerdict(RiskLevel.SAFE, scam(), RiskLevel.SUSPICIOUS, true)
    }

    @Test
    fun ruleSafeAndLowConfidenceScam_isSuspicious() {
        val lowConfidenceScam = scam().copy(confidence = Constants.CONFIDENCE_LOW)
        assertVerdict(RiskLevel.SAFE, lowConfidenceScam, RiskLevel.SUSPICIOUS, true)
    }

    @Test
    fun weakRuleWarningCanClearWithHighConfidenceLegitimateAnalysis() {
        assertVerdict(RiskLevel.SUSPICIOUS, legitimate(), RiskLevel.SAFE, false)
    }

    @Test
    fun highRiskAndAiLegitimate_cannotBecomeSafe() {
        assertVerdict(RiskLevel.SCAM, legitimate(), RiskLevel.SUSPICIOUS, true)
    }

    @Test
    fun aiUnavailablePreservesRuleVerdictWithoutInventingRisk() {
        assertVerdict(RiskLevel.SCAM, GemmaResult.fallback(), RiskLevel.SCAM, true)
        assertVerdict(RiskLevel.SAFE, GemmaResult.fallback(), RiskLevel.SAFE, false)
    }

    private fun assertVerdict(
        rule: RiskLevel,
        ai: GemmaResult,
        expectedLevel: RiskLevel,
        expectedFlagged: Boolean
    ) {
        val verdict = FinalVerdictResolver.resolve(rule, ai)
        assertEquals(expectedLevel, verdict.riskLevel)
        assertEquals(expectedFlagged, verdict.isFlagged)
    }

    private fun legitimate() = GemmaResult(
        Constants.GEMMA_LEGITIMATE,
        Constants.CONFIDENCE_HIGH,
        "No scam indicators in context."
    )

    private fun scam() = GemmaResult(
        Constants.GEMMA_SCAM,
        Constants.CONFIDENCE_HIGH,
        "The message requests credentials through a suspicious link."
    )
}
