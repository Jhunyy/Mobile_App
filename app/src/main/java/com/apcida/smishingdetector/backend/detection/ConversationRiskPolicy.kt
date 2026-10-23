package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.Constants

/** Bounded sender history; receipt time, rather than midnight, starts each window. */
object ConversationRiskPolicy {
    const val HOUR_MILLIS = 60 * 60 * 1000L
    const val CONTEXT_WINDOW_MILLIS = 48 * HOUR_MILLIS
    const val WARNING_WINDOW_MILLIS = 24 * HOUR_MILLIS
    const val MAX_CONTEXT_MESSAGES = 3

    fun historyPoints(history: List<Message>, receivedAt: Long): Int {
        val recent = history.filter {
            it.receivedAt <= receivedAt &&
                it.receivedAt >= receivedAt - WARNING_WINDOW_MILLIS &&
                it.aiAnalysisStatus == "AVAILABLE"
        }
        return when {
            recent.any {
                it.finalClassification == Constants.RISK_SCAM &&
                    it.gemmaClassification == Constants.GEMMA_SCAM &&
                    it.gemmaConfidence in setOf(Constants.CONFIDENCE_HIGH, Constants.CONFIDENCE_MEDIUM)
            } -> 25
            recent.any {
                it.gemmaClassification == Constants.GEMMA_SCAM &&
                    it.gemmaConfidence == Constants.CONFIDENCE_LOW
            } -> 10
            else -> 0
        }
    }
}
