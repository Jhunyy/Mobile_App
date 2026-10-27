package com.apcida.smishingdetector.backend.phase3f

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.network.RemoteVerdictPolicy
import org.junit.Assert.*
import org.junit.Test

class Phase3FSecurityGateTest {
    private val ready = RemoteTransportGate.Requirements(
        consent = AiProtectionConsentStore.Snapshot(true, 1000, 1),
        endpoint = "https://apcida.example.org/",
        approvedHost = "apcida.example.org",
        hasPerInstallCredential = true,
        serverIdentityValidated = true,
        backgroundRequestsReady = true,
        privacySafeErrorHandling = true,
    )

    @Test fun rejectsHttpIpWrongHostAndUrlTricks() {
        assertTrue(ProductionEndpointPolicy.isApprovedHttpsOrigin(ready.endpoint, ready.approvedHost))
        for (bad in listOf(
            "http://apcida.example.org/", "https://192.168.1.4/",
            "https://apcida.example.org.evil.test/", "https://user@apcida.example.org/",
            "https://apcida.example.org:8080/", "https://apcida.example.org/analyze",
            "https://apcida.example.org/?secret=1", "https://apcida.example.org/#fragment"
        )) assertFalse(bad, ProductionEndpointPolicy.isApprovedHttpsOrigin(bad, ready.approvedHost))
        assertFalse(ProductionEndpointPolicy.isApprovedHttpsOrigin(ready.endpoint, null))
    }

    @Test fun everyPrerequisiteIsRequiredAndRolloutRemainsOff() {
        assertTrue(RemoteTransportGate.requirementsSatisfied(ready))
        assertFalse(RemoteTransportGate.canTransmit(ready))
        assertFalse(RemoteTransportGate.requirementsSatisfied(ready.copy(
            consent = AiProtectionConsentStore.Snapshot(false, 0, 2))))
        assertFalse(RemoteTransportGate.requirementsSatisfied(ready.copy(hasPerInstallCredential = false)))
        assertFalse(RemoteTransportGate.requirementsSatisfied(ready.copy(serverIdentityValidated = false)))
        assertFalse(RemoteTransportGate.requirementsSatisfied(ready.copy(backgroundRequestsReady = false)))
        assertFalse(RemoteTransportGate.requirementsSatisfied(ready.copy(privacySafeErrorHandling = false)))
        assertFalse(RemoteTransportGate.mayTransmit("https://apcida.example.org", true))
    }

    @Test fun outagesAndAuthenticationErrorsNeverBecomeSafe() {
        for (error in listOf("OLLAMA_UNAVAILABLE", "OLLAMA_TIMEOUT", "AUTH_UNAVAILABLE", "UNAUTHORIZED")) {
            assertNull(RemoteVerdictPolicy.stateFor(error))
            assertEquals("UNVERIFIED", RemoteVerdictPolicy.localFallback("SAFE"))
        }
    }
}
