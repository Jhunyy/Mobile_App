package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdEvaluatorTest {

    private val evaluator = ThresholdEvaluator()

    @Test
    fun evaluate_mapsBoundaryScoresToExpectedRiskLevels() {
        assertEquals(RiskLevel.SAFE, evaluator.evaluate(0f))
        assertEquals(RiskLevel.SAFE, evaluator.evaluate(29f))
        assertEquals(RiskLevel.SUSPICIOUS, evaluator.evaluate(30f))
        assertEquals(RiskLevel.SUSPICIOUS, evaluator.evaluate(59f))
        assertEquals(RiskLevel.SCAM, evaluator.evaluate(60f))
    }

    @Test
    fun isHighRisk_usesConfiguredRiskThreshold() {
        assertFalse(evaluator.isHighRisk(Constants.RISK_THRESHOLD - 1f))
        assertTrue(evaluator.isHighRisk(Constants.RISK_THRESHOLD))
        assertTrue(evaluator.isHighRisk(Constants.RISK_THRESHOLD + 1f))
    }

    @Test
    fun getThreshold_returnsConfiguredRiskThreshold() {
        assertEquals(Constants.RISK_THRESHOLD, evaluator.getThreshold(), 0.001f)
    }
}
