package com.apcida.smishingdetector.backend.consent

/** Pure eligibility check shared by intake tests and production intake. */
object AutomaticAnalysisPolicy {
    fun eligible(consent: AiProtectionConsentStore.Snapshot, receivedAt: Long,
                 historicalImport: Boolean, transportReady: Boolean,
                 manualHistoricalAuthorized: Boolean = false): Boolean =
        consent.enabled && consent.grantedAt > 0 && transportReady &&
            (if (historicalImport) manualHistoricalAuthorized && receivedAt < consent.grantedAt
             else receivedAt >= consent.grantedAt)
}
