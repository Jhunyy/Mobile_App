package com.apcida.smishingdetector.backend.network

import com.apcida.smishingdetector.model.data.DetectionState

/** A completed server result is primary; a failed request has no model verdict. */
object RemoteVerdictPolicy {
    fun stateFor(classification: String): DetectionState? = when (classification) {
        "SCAM" -> DetectionState.REMOTE_SCAM
        "SUSPICIOUS" -> DetectionState.REMOTE_SUSPICIOUS
        "SAFE" -> DetectionState.REMOTE_SAFE
        else -> null
    }
    fun localFallback(ruleVerdict: String): String =
        if (ruleVerdict == "SCAM" || ruleVerdict == "SUSPICIOUS") ruleVerdict else "UNVERIFIED"
}
