package com.apcida.smishingdetector.backend.network

/** At most three automatic deletion attempts. The pending marker remains for manual retry. */
internal object ProductionRevocationRetryPolicy {
    const val MAX_ATTEMPTS = 3
    fun shouldRetry(runAttemptCount: Int): Boolean =
        runAttemptCount >= 0 && runAttemptCount + 1 < MAX_ATTEMPTS
}
