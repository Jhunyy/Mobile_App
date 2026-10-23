package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.RiskLevel

/** A narrow exception for greetings with no independent risk signal. */
object SimpleGreetingPolicy {
    private val greeting = Regex(
        "(?iu)^(?:hi|hello|hey|kumusta|kamusta|musta|good morning|good afternoon|good evening)(?:[,\\s]+[\\p{L}]+)?[!?.,\\s]*$"
    )
    private val actionWord = Regex(
        "(?iu)\\b(?:send|share|provide|enter|reply|pay|transfer|deposit|click|open|visit|claim|verify|login|log|ibigay|ipadala|bayaran|i-click|otp|pin|password|cvv|money|cash|fee|payment|account)\\b"
    )

    fun isEligible(
        body: String,
        ruleScore: Float,
        hasStrongRuleSignal: Boolean,
        senderPoints: Int
    ): Boolean =
        ruleScore == 0f && !hasStrongRuleSignal && senderPoints == 0 &&
            UrlCandidates.extract(body).isEmpty() &&
            !actionWord.containsMatchIn(body) && greeting.matches(body.trim())

    fun verdict(historyPoints: Int): RiskLevel =
        if (historyPoints == 25) RiskLevel.SUSPICIOUS else RiskLevel.SAFE
}
