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
    fun ruleSafeAndAiScam_isScam() {
        assertVerdict(RiskLevel.SAFE, scam(), RiskLevel.SCAM, true)
    }

    @Test
    fun ruleSuspiciousAndAiLegitimate_remainsSuspicious() {
        assertVerdict(RiskLevel.SUSPICIOUS, legitimate(), RiskLevel.SUSPICIOUS, true)
    }

    @Test
    fun highRiskAndAiLegitimate_cannotBecomeSafe() {
        assertVerdict(RiskLevel.SCAM, legitimate(), RiskLevel.SUSPICIOUS, true)
    }

    @Test
    fun aiUnavailable_preservesHighRiskAndKeepsRuleSafeUnderReview() {
        assertVerdict(RiskLevel.SCAM, GemmaResult.fallback(), RiskLevel.SCAM, true)
        assertVerdict(RiskLevel.SAFE, GemmaResult.fallback(), RiskLevel.SUSPICIOUS, true)
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
