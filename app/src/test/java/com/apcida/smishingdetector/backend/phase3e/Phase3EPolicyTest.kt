package com.apcida.smishingdetector.backend.phase3e

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.consent.AutomaticAnalysisPolicy
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.network.RemoteVerdictPolicy
import com.apcida.smishingdetector.model.data.DetectionState
import org.junit.Assert.*
import org.junit.Test

class Phase3EPolicyTest {
    private val off = AiProtectionConsentStore.Snapshot(false, 0, 0)
    private val on = AiProtectionConsentStore.Snapshot(true, 1000, 2)

    @Test fun noTransmissionBeforeConsentOrForHistory() {
        assertFalse(AutomaticAnalysisPolicy.eligible(off, 2000, false, true))
        assertFalse(AutomaticAnalysisPolicy.eligible(on, 999, false, true))
        assertFalse(AutomaticAnalysisPolicy.eligible(on, 2000, true, true))
        assertTrue(AutomaticAnalysisPolicy.eligible(on, 2000, false, true))
    }

    @Test fun insecureOrUnprovisionedTransportIsClosed() {
        assertFalse(RemoteTransportGate.mayTransmit("http://192.168.1.2:3000", true))
        assertFalse(RemoteTransportGate.mayTransmit("https://example.org", false))
        assertFalse(AutomaticAnalysisPolicy.eligible(on, 2000, false,
            RemoteTransportGate.mayTransmit("https://example.org", true)))
    }

    @Test fun serverVerdictsAndFailuresStayDistinct() {
        assertEquals(DetectionState.REMOTE_SCAM, RemoteVerdictPolicy.stateFor("SCAM"))
        assertEquals(DetectionState.REMOTE_SAFE, RemoteVerdictPolicy.stateFor("SAFE"))
        assertEquals(DetectionState.REMOTE_SUSPICIOUS, RemoteVerdictPolicy.stateFor("SUSPICIOUS"))
        assertNull(RemoteVerdictPolicy.stateFor("OLLAMA_TIMEOUT"))
        assertEquals("UNVERIFIED", RemoteVerdictPolicy.localFallback("SAFE"))
        assertEquals("SCAM", RemoteVerdictPolicy.localFallback("SCAM"))
    }
}
