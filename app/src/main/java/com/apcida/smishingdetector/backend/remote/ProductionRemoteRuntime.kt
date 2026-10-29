package com.apcida.smishingdetector.backend.remote

import android.content.Context
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import com.apcida.smishingdetector.backend.network.RemoteTransportGate

/** Production-only configuration. The gate remains closed in this build. */
internal object ProductionRemoteRuntime {
    fun requirements(context: Context,
                     consent: AiProtectionConsentStore.Snapshot): RemoteTransportGate.Requirements {
        val host = BuildConfig.PRODUCTION_APPROVED_HOST.takeIf { it.isNotBlank() }
        val origin = host?.let { "https://$it" }
        val approved = ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, host) &&
            !host.orEmpty().endsWith(".trycloudflare.com", ignoreCase = true)
        val credentialPresent = approved &&
            runCatching { ProductionCredentialStore(context.applicationContext).get() != null }
                .getOrDefault(false) &&
            runCatching { CloudflareAccessCredentialStore(context.applicationContext).get() != null }
                .getOrDefault(false)
        return RemoteTransportGate.Requirements(
            consent, origin, host, credentialPresent,
            serverIdentityValidated = approved,
            backgroundRequestsReady = true,
            privacySafeErrorHandling = true
        )
    }

    fun canSchedule(context: Context, consent: AiProtectionConsentStore.Snapshot): Boolean =
        RemoteTransportGate.PRODUCTION_READY &&
            RemoteTransportGate.canTransmit(requirements(context, consent))
}
