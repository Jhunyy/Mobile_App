package com.apcida.smishingdetector.backend.network

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore

/** Automatic real-SMS transport remains closed until rollout and every security prerequisite. */
object RemoteTransportGate {
    const val PRODUCTION_READY = false

    data class Requirements(
        val consent: AiProtectionConsentStore.Snapshot,
        val endpoint: String?,
        val approvedHost: String?,
        val hasPerInstallCredential: Boolean,
        val serverIdentityValidated: Boolean,
        val backgroundRequestsReady: Boolean,
        val privacySafeErrorHandling: Boolean,
    )

    fun requirementsSatisfied(value: Requirements): Boolean =
        value.consent.enabled && value.consent.grantedAt > 0 &&
            ProductionEndpointPolicy.isApprovedHttpsOrigin(value.endpoint, value.approvedHost) &&
            value.hasPerInstallCredential && value.serverIdentityValidated &&
            value.backgroundRequestsReady && value.privacySafeErrorHandling

    fun canTransmit(value: Requirements): Boolean = PRODUCTION_READY && requirementsSatisfied(value)

    /** Phase 3E compatibility path has no approved host or production credential provider. */
    fun mayTransmit(endpoint: String?, hasPerInstallCredential: Boolean): Boolean =
        canTransmit(Requirements(
            AiProtectionConsentStore.Snapshot(false, 0, 0), endpoint, null,
            hasPerInstallCredential, false, false, false
        ))
}
