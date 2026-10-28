package com.apcida.smishingdetector.view.presentation

import com.apcida.smishingdetector.model.entity.Message

/** A display-only interpretation of persisted detection state. Never grants a verdict. */
object DetectionPresentation {
    enum class Tone { SCAM, SUSPICIOUS, UNVERIFIED, SAFE }

    data class State(
        val verdict: String,
        val badge: String,
        val explanation: String,
        val tone: Tone,
        val aiVerified: Boolean,
        val aiPending: Boolean,
        val confidence: String? = null,
        val rationale: String? = null,
    ) {
        val isSafeVerified: Boolean get() = aiVerified && verdict == "SAFE"
    }

    fun forMessage(message: Message): State {
        val claimed = message.finalClassification
        val remoteState = when (claimed) {
            "SCAM" -> "REMOTE_SCAM"
            "SUSPICIOUS" -> "REMOTE_SUSPICIOUS"
            "SAFE" -> "REMOTE_SAFE"
            else -> null
        }
        val verified = remoteState != null && message.verdictSource == "REMOTE_GEMMA_RAG" &&
            message.detectionState == remoteState && message.processingState == "COMPLETED" &&
            message.aiAnalysisStatus == "AVAILABLE" && message.gemmaInvoked &&
            message.gemmaClassification == claimed &&
            message.gemmaConfidence in setOf("HIGH", "MEDIUM", "LOW") &&
            !message.gemmaRationale.isNullOrBlank()
        if (verified) {
            return State(claimed, "$claimed · REMOTE AI", 
                "Remote Gemma 3 12B + RAG: ${message.gemmaRationale}", tone(claimed),
                aiVerified = true, aiPending = false,
                confidence = message.gemmaConfidence, rationale = message.gemmaRationale)
        }

        val local = when {
            message.verdictSource == "REMOTE_GEMMA_RAG" ->
                message.deterministicRiskLevel.takeIf { it == "SCAM" || it == "SUSPICIOUS" }
            claimed == "SCAM" || claimed == "SUSPICIOUS" -> claimed
            else -> null
        } ?: "UNVERIFIED"
        val checkingRules = message.processingState == "PENDING"
        val pending = message.verdictSource == "LOCAL_RULES" &&
            (message.detectionState == "REMOTE_PENDING" ||
                message.processingState in setOf("AI_QUEUED", "AI_ANALYZING"))
        val legacy = message.verdictSource == "LEGACY_ON_DEVICE"
        val suffix = when {
            checkingRules -> "LOCAL CHECK"
            pending -> "AI PENDING"
            message.detectionState == "REMOTE_UNAVAILABLE" -> "AI UNAVAILABLE"
            legacy -> "OLDER RESULT"
            message.detectionState == "REMOTE_CONSENT_DISABLED" -> "AI OFF"
            else -> "RULES ONLY"
        }
        val explanation = when {
            checkingRules -> "Checking local keyword indicators. No AI verdict is available yet."
            pending -> "Local keyword result is preliminary. Remote AI analysis is pending and may take a couple of minutes. You can keep using ApCiDa."
            message.detectionState == "REMOTE_UNAVAILABLE" -> "Remote AI analysis did not complete. The local keyword result remains; this message has not been AI verified."
            legacy -> "This older result was not verified by the current remote AI service. Review the message independently."
            message.detectionState == "REMOTE_CONSENT_DISABLED" -> "Remote analysis was not enabled for this message. Only local keyword screening was completed."
            else -> "Only local keyword screening was completed. No remote AI verdict is available."
        }
        return State(local, "$local · $suffix", explanation, tone(local),
            aiVerified = false, aiPending = pending)
    }

    private fun tone(verdict: String) = when (verdict) {
        "SCAM" -> Tone.SCAM
        "SUSPICIOUS" -> Tone.SUSPICIOUS
        "SAFE" -> Tone.SAFE
        else -> Tone.UNVERIFIED
    }
}
