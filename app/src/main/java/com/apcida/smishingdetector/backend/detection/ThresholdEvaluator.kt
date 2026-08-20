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
        // 60+ = SCAM (triggers Gemma Stage 2)
    }

    /**
     * Evaluates the risk score and returns the appropriate RiskLevel.
     *
     * SAFE       — score below 30 — no Gemma invocation
     * SUSPICIOUS — score 30 to 59 — Gemma invoked for validation
     * SCAM       — score 60 and above — Gemma invoked for validation
     *
     * Both SUSPICIOUS and SCAM trigger Stage 2 Gemma validation.
     * Final classification is determined by Gemma's output.
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

    /**
     * Returns true if the score is high enough to trigger
     * Stage 2 Gemma contextual validation.
     */
    fun shouldInvokeGemma(score: Float): Boolean {
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