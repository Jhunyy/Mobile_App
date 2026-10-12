package com.apcida.smishingdetector.backend.detection

/** Gives a saved phone number a small score discount without changing its risk band. */
object ContactRiskAdjustment {
    private const val CONTACT_DISCOUNT = 5f

    fun apply(score: Float, isSavedContact: Boolean): Float {
        if (!isSavedContact) return score

        val bandMinimum = when {
            score >= 60f -> 60f
            score >= 30f -> 30f
            else -> 0f
        }
        return (score - CONTACT_DISCOUNT).coerceAtLeast(bandMinimum)
    }
}
