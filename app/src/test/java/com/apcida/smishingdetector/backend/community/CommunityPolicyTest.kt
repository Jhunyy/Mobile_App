package com.apcida.smishingdetector.backend.community

import org.junit.Assert.*
import org.junit.Test

class CommunityPolicyTest {
    @Test fun codesAreRedactedBeforeReviewWhileOtpNoticesRemainEligible() {
        assertFalse(CommunityPolicy.eligible("Your OTP is 123456"))
        assertTrue(CommunityPolicy.eligible(CommunityPolicy.redact("Your OTP is 123456")))
        assertFalse(CommunityPolicy.redact("Your OTP is 123456").contains("123456"))
        assertTrue(CommunityPolicy.eligible(CommunityPolicy.redact("Call 0917-123-4567")))
    }

    @Test fun redactsIdentifiersBeforeUpload() {
        val text = CommunityPolicy.redact("Visit https://bad.example/path, email a@b.com or call 09171234567")
        assertFalse(text.contains("bad.example"))
        assertFalse(text.contains("a@b.com"))
        assertFalse(text.contains("09171234567"))
        assertFalse(CommunityPolicy.redact("Call 0917-123-4567").contains("0917"))
    }

    @Test fun deduplicatesWhitespaceAndTracksRemainingLimit() {
        assertEquals(CommunityPolicy.fingerprint("Hello  world"), CommunityPolicy.fingerprint(" hello world "))
        assertEquals(CommunityPolicy.fingerprint("Hello  world", ByteArray(32) { 1 }),
            CommunityPolicy.fingerprint(" hello world ", ByteArray(32) { 1 }))
        assertNotEquals(CommunityPolicy.fingerprint("Hello world", ByteArray(32) { 1 }),
            CommunityPolicy.fingerprint("Hello world", ByteArray(32) { 2 }))
        assertEquals(28, CommunityPolicy.remaining(12))
        assertEquals(20, CommunityPolicy.remaining(20))
        assertEquals(0, CommunityPolicy.remaining(40))
    }
}
