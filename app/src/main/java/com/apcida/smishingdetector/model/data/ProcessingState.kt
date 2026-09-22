package com.apcida.smishingdetector.model.data

/** Persisted lifecycle for an incoming SMS and its on-device AI analysis. */
enum class ProcessingState {
    PENDING,
    RULE_ANALYZED,
    AI_QUEUED,
    AI_ANALYZING,
    COMPLETED,
    AI_FAILED;

    fun canTransitionTo(next: ProcessingState): Boolean = next in when (this) {
        PENDING -> setOf(RULE_ANALYZED)
        RULE_ANALYZED -> setOf(AI_QUEUED)
        AI_QUEUED -> setOf(AI_ANALYZING)
        AI_ANALYZING -> setOf(COMPLETED, AI_FAILED)
        AI_FAILED -> setOf(AI_ANALYZING)
        COMPLETED -> emptySet()
    }
}
