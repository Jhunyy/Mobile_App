package com.apcida.smishingdetector.backend.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSignalsTest {
    @Test
    fun distinguishesCredentialRequestsFromRoutineOtpNotices() {
        assertFalse(MessageSignals.hasHighRiskRequest("Your one-time OTP is 123456."))
        assertTrue(MessageSignals.hasHighRiskRequest("Please send your OTP to this number."))
    }

    @Test
    fun ordinaryReminderHasNoActionableSignal() {
        assertFalse(MessageSignals.hasActionableSignal(
            "Hi! Just reminding you that our meeting is tomorrow at 10 AM. See you then."
        ))
    }
}
