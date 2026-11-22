package com.apcida.smishingdetector.backend.community

import com.google.gson.JsonParser

/** Removes only server-acknowledged decisions, so transient failures leave the batch retryable. */
object CommunitySubmissionPolicy {
    fun hasLegacyParticipantLabels(serializedBatch: String): Boolean =
        JsonParser().parse(serializedBatch).asJsonObject.getAsJsonArray("items")
            ?.any { it.asJsonObject.has("reviewedCategory") } == true

    fun remaining(batch: ContributionBatch, response: ContributionResponse): List<ContributionItem> {
        val submitted = batch.items.map { it.clientId }.toSet()
        val accepted = response.acceptedClientIds.toSet()
        val rejected = response.rejectedClientIds.toSet()
        require(accepted.intersect(rejected).isEmpty()) { "Server returned conflicting decisions." }
        require((accepted + rejected).all { it in submitted }) { "Server returned an unknown message ID." }
        return batch.items.filter { it.clientId !in accepted && it.clientId !in rejected }
    }
}
