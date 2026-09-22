package com.apcida.smishingdetector.backend.detection

import android.util.Log
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

class ThresholdEvaluator {

    companion object {
        private const val TAG = "ThresholdEvaluator"

        // Score ranges for each risk level
        // These can be adjusted during testing/calibration
        private const val SAFE_MAX = 29f           // 0 - 29 = SAFE
        private const val SUSPICIOUS_MAX = 59f     // 30 - 59 = SUSPICIOUS
        // 60+ = SCAM by deterministic analysis
    }

    /**
     * Evaluates the risk score and returns the appropriate RiskLevel.
     *
     * SAFE       — score below 30
     * SUSPICIOUS — score 30 to 59
     * SCAM       — score 60 and above
     *
     * This is an independent rule-based signal. Gemma runs for every message,
     * so this score no longer controls whether contextual analysis is invoked.
     */
    fun evaluate(score: Float): RiskLevel {
        val level = when {
            score <= SAFE_MAX -> RiskLevel.SAFE
            score <= SUSPICIOUS_MAX -> RiskLevel.SUSPICIOUS
            else -> RiskLevel.SCAM
        }

        Log.d(TAG, "Score: $score | Threshold: ${Constants.RISK_THRESHOLD} | Level: $level")
        return level
    }

    /** Returns true when deterministic evidence reaches the high-risk boundary. */
    fun isHighRisk(score: Float): Boolean {
        return score >= Constants.RISK_THRESHOLD
    }

    /**
     * Returns the threshold value currently in use.
     * Useful for displaying in evaluation reports.
     */
    fun getThreshold(): Float {
        return Constants.RISK_THRESHOLD
    }
}
