package com.apcida.smishingdetector.backend.community

import org.junit.Assert.*
import org.junit.Test
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel

class CommunityPolicyTest {
    @Test fun authenticationCodesAreNeverCandidates() {
        assertFalse(CommunityPolicy.eligible("Your OTP is 123456"))
        assertFalse(CommunityPolicy.eligible("Your verification code is ABCDEF"))
        assertFalse(CommunityPolicy.eligible("Code 1234 for your account"))
        assertFalse(CommunityPolicy.eligible("Call 0917-123-4567"))
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
        assertEquals(8, CommunityPolicy.remaining(12))
        assertEquals(0, CommunityPolicy.remaining(20))
        assertEquals(0, CommunityPolicy.remaining(25))
    }

    @Test fun uncertainAndFailedInferenceNeverBecomeCandidates() {
        val highScam = GemmaResult("SCAM", "HIGH", "Suspicious link")
        assertEquals("SCAM", CommunityPolicy.candidateCategory(RiskLevel.SAFE, highScam,
            RiskLevel.SCAM, 1, 1))
        assertNull(CommunityPolicy.candidateCategory(RiskLevel.SAFE, highScam.copy(confidence = "LOW"),
            RiskLevel.SCAM, 1, 1))
        assertNull(CommunityPolicy.candidateCategory(RiskLevel.SAFE, GemmaResult.fallback(),
            RiskLevel.SUSPICIOUS, 1, 1))
        assertNull(CommunityPolicy.candidateCategory(RiskLevel.SAFE, highScam,
            RiskLevel.SCAM, 0, 1))
    }
}
