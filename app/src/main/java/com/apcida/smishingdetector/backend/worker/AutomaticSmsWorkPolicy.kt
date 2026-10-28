package com.apcida.smishingdetector.backend.worker

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.ProductionTiming
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.model.entity.Message

/** Pure decision for a Room row before any production network operation. */
internal object AutomaticSmsWorkPolicy {
    enum class Decision { READY, CONSENT_DISABLED, STALE, ALREADY_HANDLED }

    fun decide(message: Message, consent: AiProtectionConsentStore.Snapshot,
               now: Long): Decision {
        if (message.verdictSource != "LOCAL_RULES") return Decision.ALREADY_HANDLED
        if (!consent.enabled || consent.generation != message.consentGeneration)
            return Decision.CONSENT_DISABLED
        if (message.processingState == ProcessingState.AI_ANALYZING.name) return Decision.STALE
        if (message.processingState != ProcessingState.AI_QUEUED.name ||
            message.detectionState != "REMOTE_PENDING") return Decision.ALREADY_HANDLED
        if (now - message.createdAt >= ProductionTiming.ORPHAN_STALE_MS ||
            message.receivedAt < consent.grantedAt) return Decision.STALE
        return Decision.READY
    }
}
