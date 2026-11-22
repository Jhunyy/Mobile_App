package com.apcida.smishingdetector.backend.community

import org.junit.Assert.*
import org.junit.Test

class CommunitySubmissionPolicyTest {
    private val one = ContributionItem("one", "one", "SCAM", 1)
    private val two = ContributionItem("two", "two", "SAFE", 1)
    private val batch = ContributionBatch("batch", "participant", 1, "STUDY_EVALUATION_ONLY",
        2, 2, "RECENT_INBOX_PARTICIPANT_REVIEW", listOf(one, two))

    @Test fun onlyAcknowledgedItemsLeaveRetryQueue() {
        val response = ContributionResponse(listOf("one"), emptyList(), ContributionProgress(1))
        assertEquals(listOf(two), CommunitySubmissionPolicy.remaining(batch, response))
        assertEquals(batch.items, CommunitySubmissionPolicy.remaining(batch,
            ContributionResponse(emptyList(), emptyList(), ContributionProgress(0))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownAcknowledgement() {
        CommunitySubmissionPolicy.remaining(batch,
            ContributionResponse(listOf("other"), emptyList(), ContributionProgress(1)))
    }

    @Test fun legacyLabelsAreDetectedOnlyAsItemFields() {
        val old = """{"items":[{"text":"hello","reviewedCategory":"SAFE"}]}"""
        val current = """{"items":[{"text":"reviewedCategory is just text"}]}"""
        assertTrue(CommunitySubmissionPolicy.hasLegacyParticipantLabels(old))
        assertFalse(CommunitySubmissionPolicy.hasLegacyParticipantLabels(current))
    }
}
