package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleGreetingPolicyTest {
    @Test
    fun harmlessGreetingsCanStaySafeDespiteAnAiScamResult() {
        listOf("hi jem", "Hi, Jem!", "hello", "Good morning Jem", "Kamusta Ana?").forEach {
            assertTrue(it, SimpleGreetingPolicy.isEligible(it, 0f, false, 0))
        }
        assertEquals(RiskLevel.SAFE, SimpleGreetingPolicy.verdict(0))
        assertEquals(RiskLevel.SAFE, SimpleGreetingPolicy.verdict(10))
        assertEquals(RiskLevel.SUSPICIOUS, SimpleGreetingPolicy.verdict(25))
    }

    @Test
    fun shortMessagesWithRequestsLinksOrRuleSignalsAreNotExempt() {
        listOf("send OTP", "pay now", "hi send", "hi click", "hi otp", "hi jem bit.ly/x",
            "hi 123456", "hello, claim reward").forEach {
            assertFalse(it, SimpleGreetingPolicy.isEligible(it, 0f, false, 0))
        }
        assertFalse(SimpleGreetingPolicy.isEligible("hi jem", 10f, false, 0))
        assertFalse(SimpleGreetingPolicy.isEligible("hi jem", 0f, true, 0))
        assertFalse(SimpleGreetingPolicy.isEligible("hi jem", 0f, false, 5))
    }

    @Test
    fun confirmedScamWarnsForExactlyTwentyFourHours() {
        val now = 200_000_000L
        val priorScam = Message(
            content = "Send your OTP",
            receivedAt = now - ConversationRiskPolicy.WARNING_WINDOW_MILLIS,
            aiAnalysisStatus = "AVAILABLE",
            gemmaClassification = "SCAM",
            gemmaConfidence = "HIGH",
            finalClassification = "SCAM"
        )

        assertEquals(RiskLevel.SUSPICIOUS, SimpleGreetingPolicy.verdict(
            ConversationRiskPolicy.historyPoints(listOf(priorScam), now)
        ))
        assertEquals(RiskLevel.SAFE, SimpleGreetingPolicy.verdict(
            ConversationRiskPolicy.historyPoints(listOf(priorScam), now + 1)
        ))
    }
}
