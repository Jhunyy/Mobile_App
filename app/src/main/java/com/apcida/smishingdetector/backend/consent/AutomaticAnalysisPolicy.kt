package com.apcida.smishingdetector.backend.consent

/** Pure eligibility check shared by intake tests and production intake. */
object AutomaticAnalysisPolicy {
    fun eligible(consent: AiProtectionConsentStore.Snapshot, receivedAt: Long,
                 historicalImport: Boolean, transportReady: Boolean): Boolean =
        consent.enabled && consent.grantedAt > 0 && receivedAt >= consent.grantedAt &&
            !historicalImport && transportReady
}
