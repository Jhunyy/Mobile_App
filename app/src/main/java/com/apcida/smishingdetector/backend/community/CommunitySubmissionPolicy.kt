package com.apcida.smishingdetector.backend.community

/** Removes only server-acknowledged decisions, so transient failures leave the batch retryable. */
object CommunitySubmissionPolicy {
    fun remaining(batch: ContributionBatch, response: ContributionResponse): List<ContributionItem> {
        val submitted = batch.items.map { it.clientId }.toSet()
        val accepted = response.acceptedClientIds.toSet()
        val rejected = response.rejectedClientIds.toSet()
        require(accepted.intersect(rejected).isEmpty()) { "Server returned conflicting decisions." }
        require((accepted + rejected).all { it in submitted }) { "Server returned an unknown message ID." }
        return batch.items.filter { it.clientId !in accepted && it.clientId !in rejected }
    }
}
