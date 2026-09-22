package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.util.HashUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePolicyTest {

    @Test
    fun duplicateWindow_isBounded() {
        val now = 1_000_000L
        assertTrue(DuplicatePolicy.isWithinWindow(now - DuplicatePolicy.WINDOW_MILLIS, now))
        assertFalse(DuplicatePolicy.isWithinWindow(now - DuplicatePolicy.WINDOW_MILLIS - 1, now))
    }

    @Test
    fun senderHashDistinguishesSameContentFromDifferentSenders() {
        assertNotEquals(HashUtil.hashSender("Sender A"), HashUtil.hashSender("Sender B"))
    }
}
