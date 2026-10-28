package com.apcida.smishingdetector.view.presentation

import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class NotificationCopyTest {
    private fun result(ai: Boolean, verdict: String) = DetectionResult(
        messageContent = "Synthetic sensitive code 123456", riskScore = 0f,
        riskLevel = RiskLevel.valueOf(verdict), isFlagged = true,
        matchedKeywords = emptyList(), gemmaInvoked = ai,
        finalClassification = verdict, finalRationale = "Synthetic private rationale")

    @Test fun localWarningIsClearlyPreliminaryAndPrivate() {
        val item = result(false, "SCAM")
        assertTrue(NotificationCopy.summary(item).contains("Local keyword"))
        assertTrue(NotificationCopy.explanation(item).contains("not verified"))
        assertFalse(NotificationCopy.summary(item).contains("123456"))
    }

    @Test fun remoteWarningsShowSourceWithoutLeakingSmsOrRationale() {
        for (label in listOf("SCAM", "SUSPICIOUS")) {
            val item = result(true, label)
            assertTrue(NotificationCopy.summary(item).contains("Remote AI"))
            assertFalse(NotificationCopy.summary(item).contains("123456"))
            assertFalse(NotificationCopy.explanation(item).contains("private rationale"))
        }
    }
}
