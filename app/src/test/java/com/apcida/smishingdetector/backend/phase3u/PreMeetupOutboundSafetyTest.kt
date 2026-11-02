package com.apcida.smishingdetector.backend.phase3u

import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import org.junit.Assert.*
import org.junit.Test

class PreMeetupOutboundSafetyTest {
    @Test fun onlyTheExplicitPilotCanOpenAutomaticAiAndOptionalSharingStaysClosed() {
        assertEquals(BuildConfig.REAL_SMS_PILOT_ENABLED, RemoteTransportGate.PRODUCTION_READY)
        assertEquals("", BuildConfig.REPORT_API_KEY)
        assertEquals("", BuildConfig.COMMUNITY_API_KEY)
    }
}
