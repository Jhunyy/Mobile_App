package com.apcida.smishingdetector.view.presentation

import com.apcida.smishingdetector.model.entity.Message
import org.junit.Assert.*
import org.junit.Test

class DetectionPresentationTest {
    private fun row() = Message(content = "Synthetic message", receivedAt = 2_000,
        finalClassification = "UNVERIFIED", verdictSource = "LOCAL_RULES",
        deterministicRiskLevel = "SAFE", detectionState = "REMOTE_CONSENT_DISABLED",
        processingState = "REMOTE_CONSENT_DISABLED")

    private fun verified(label: String) = row().copy(
        finalClassification = label, gemmaClassification = label,
        gemmaConfidence = "HIGH", gemmaRationale = "Synthetic rationale",
        gemmaInvoked = true, verdictSource = "REMOTE_GEMMA_RAG",
        detectionState = "REMOTE_$label", processingState = "COMPLETED",
        aiAnalysisStatus = "AVAILABLE")

    @Test fun onlyCompleteMatchingRemoteResultCanAppearSafe() {
        val safe = DetectionPresentation.forMessage(verified("SAFE"))
        assertTrue(safe.isSafeVerified)
        assertEquals("SAFE", safe.verdict)
        assertEquals("HIGH", safe.confidence)
        assertTrue(safe.explanation.contains("Synthetic rationale"))
        val inconsistent = listOf(
            verified("SAFE").copy(verdictSource = "LOCAL_RULES"),
            verified("SAFE").copy(detectionState = "REMOTE_UNAVAILABLE"),
            verified("SAFE").copy(gemmaClassification = "SCAM"),
            verified("SAFE").copy(gemmaRationale = null),
            verified("SAFE").copy(processingState = "AI_ANALYZING"),
            row().copy(finalClassification = "SAFE"),
            row().copy(finalClassification = "SAFE", verdictSource = "LEGACY_ON_DEVICE")
        )
        inconsistent.forEach {
            val state = DetectionPresentation.forMessage(it)
            assertFalse(state.isSafeVerified)
            assertNotEquals("SAFE", state.verdict)
        }
    }

    @Test fun remoteScamAndSuspiciousShowSourceAndRationale() {
        for (label in listOf("SCAM", "SUSPICIOUS")) {
            val state = DetectionPresentation.forMessage(verified(label))
            assertTrue(state.aiVerified)
            assertEquals(label, state.verdict)
            assertTrue(state.badge.contains("REMOTE AI"))
            assertEquals("Synthetic rationale", state.rationale)
        }
    }

    @Test fun localPendingFailureAndRevocationRemainPreliminary() {
        val pending = DetectionPresentation.forMessage(row().copy(
            finalClassification = "SCAM", detectionState = "REMOTE_PENDING",
            processingState = "AI_ANALYZING"))
        assertTrue(pending.aiPending)
        assertEquals("SCAM", pending.verdict)
        assertTrue(pending.badge.contains("AI PENDING"))
        val unavailable = DetectionPresentation.forMessage(row().copy(
            detectionState = "REMOTE_UNAVAILABLE", processingState = "REMOTE_UNAVAILABLE"))
        assertEquals("UNVERIFIED", unavailable.verdict)
        assertTrue(unavailable.badge.contains("AI UNAVAILABLE"))
        assertFalse(DetectionPresentation.forMessage(row()).aiVerified)
        assertTrue(DetectionPresentation.forMessage(row()).badge.contains("AI OFF"))
    }

    @Test fun corruptRemoteRowFallsBackToStrongLocalWarning() {
        val corrupt = verified("SAFE").copy(gemmaRationale = null, deterministicRiskLevel = "SCAM")
        val state = DetectionPresentation.forMessage(corrupt)
        assertEquals("SCAM", state.verdict)
        assertFalse(state.aiVerified)
    }
}
