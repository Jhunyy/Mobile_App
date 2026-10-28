package com.apcida.smishingdetector.backend.worker

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.model.entity.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSmsWorkPolicyTest {
    private val consent = AiProtectionConsentStore.Snapshot(true, 1_000, 2)
    private fun queued() = Message(content = "Synthetic SMS", receivedAt = 2_000,
        createdAt = 2_000, processingState = "AI_QUEUED",
        detectionState = "REMOTE_PENDING", verdictSource = "LOCAL_RULES",
        consentGeneration = 2)

    @Test fun freshConsentedRowIsReady() {
        assertEquals(AutomaticSmsWorkPolicy.Decision.READY,
            AutomaticSmsWorkPolicy.decide(queued(), consent, 3_000))
    }

    @Test fun consentRevocationAndGenerationChangeBlockWork() {
        assertEquals(AutomaticSmsWorkPolicy.Decision.CONSENT_DISABLED,
            AutomaticSmsWorkPolicy.decide(queued(), consent.copy(enabled = false), 3_000))
        assertEquals(AutomaticSmsWorkPolicy.Decision.CONSENT_DISABLED,
            AutomaticSmsWorkPolicy.decide(queued(), consent.copy(generation = 3), 3_000))
    }

    @Test fun staleOrRestartedWorkFallsBackWithoutSending() {
        assertEquals(AutomaticSmsWorkPolicy.Decision.STALE,
            AutomaticSmsWorkPolicy.decide(queued(), consent, 200_000))
        assertEquals(AutomaticSmsWorkPolicy.Decision.STALE,
            AutomaticSmsWorkPolicy.decide(queued().copy(processingState = "AI_ANALYZING"),
                consent, 3_000))
    }

    @Test fun historicalAndCompletedRowsCannotSend() {
        assertEquals(AutomaticSmsWorkPolicy.Decision.ALREADY_HANDLED,
            AutomaticSmsWorkPolicy.decide(queued().copy(detectionState = "REMOTE_CONSENT_DISABLED"),
                consent, 3_000))
        assertEquals(AutomaticSmsWorkPolicy.Decision.ALREADY_HANDLED,
            AutomaticSmsWorkPolicy.decide(queued().copy(processingState = "COMPLETED",
                verdictSource = "REMOTE_GEMMA_RAG"), consent, 3_000))
    }
}
