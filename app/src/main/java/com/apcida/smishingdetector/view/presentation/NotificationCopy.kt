package com.apcida.smishingdetector.view.presentation

import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.RiskLevel

/** Notification copy does not include SMS text, sender or model rationale. */
object NotificationCopy {
    fun title(result: DetectionResult): String = when {
        !result.gemmaInvoked -> "High-risk SMS detected"
        result.riskLevel == RiskLevel.SUSPICIOUS -> "Suspicious SMS requires review"
        else -> "Potential scam SMS detected"
    }

    fun summary(result: DetectionResult): String = if (!result.gemmaInvoked)
        "Local keyword warning. Rule score: ${result.riskScore.toInt()}. Tap to review."
    else "Remote AI marked this SMS ${result.finalClassification.lowercase()}. Tap to review."

    fun explanation(result: DetectionResult): String = when {
        !result.gemmaInvoked ->
            "Local rules found strong scam indicators. Remote AI has not verified this message."
        result.riskLevel == RiskLevel.SUSPICIOUS ->
            "Remote AI marked this message suspicious. Review it before taking action."
        else ->
            "Remote AI marked this message as a potential scam. Review the result before taking action."
    }
}
