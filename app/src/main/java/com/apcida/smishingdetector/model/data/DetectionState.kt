package com.apcida.smishingdetector.model.data

/** UNVERIFIED is an assessment state, never a fourth server classification. */
enum class DetectionState {
    LOCAL_PROVISIONAL, REMOTE_PENDING, REMOTE_SCAM, REMOTE_SUSPICIOUS,
    REMOTE_SAFE, REMOTE_UNAVAILABLE, REMOTE_CONSENT_DISABLED, LEGACY_ON_DEVICE
}

enum class VerdictSource { LOCAL_RULES, REMOTE_GEMMA_RAG, LEGACY_ON_DEVICE }
