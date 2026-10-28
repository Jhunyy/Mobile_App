package com.apcida.smishingdetector.view.presentation

import org.junit.Assert.*
import org.junit.Test

class ProtectionStatusPresentationTest {
    @Test fun closedGateAndConsentStateNeverClaimRemoteAvailability() {
        assertTrue(ProtectionStatusPresentation.remote(false, false, false).contains("off"))
        val consented = ProtectionStatusPresentation.remote(true, false, true)
        assertTrue(consented.contains("no automatic SMS is sent"))
        assertTrue(ProtectionStatusPresentation.remote(true, true, true).contains("not been checked"))
    }

    @Test fun localAndEnrollmentStatusRemainSeparate() {
        assertTrue(ProtectionStatusPresentation.local(true).contains("unverified"))
        assertTrue(ProtectionStatusPresentation.local(false).contains("SMS permission"))
        assertTrue(ProtectionStatusPresentation.credential(false, false, false).contains("not enrolled"))
        assertTrue(ProtectionStatusPresentation.credential(true, true, false).contains("rejected"))
        assertTrue(ProtectionStatusPresentation.credential(true, false, true).contains("analysis blocked"))
    }
}
