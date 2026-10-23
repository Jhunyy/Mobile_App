package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword

/** Broad, inspectable signals used by verdict policies. */
object MessageSignals {
    private const val STRONG_URL_WEIGHT = 30f

    private val credentialRequest = Regex(
        "(?iu)\\b(?:send|share|provide|enter|reply with|ibigay|ipadala|ilagay)\\b.{0,50}\\b(?:otp|pin|password|cvv|verification code)\\b"
    )
    private val paymentRequest = Regex(
        "(?iu)\\b(?:send|pay|transfer|deposit|magbayad|bayaran|ipadala)\\b.{0,50}\\b(?:money|cash|funds|fee|payment|pesos|php|bank account)\\b"
    )

    fun hasHighRiskRequest(message: String): Boolean =
        credentialRequest.containsMatchIn(message) || paymentRequest.containsMatchIn(message)

    fun hasStrongRuleSignal(message: String, matchedKeywords: List<Keyword>): Boolean =
        hasHighRiskRequest(message) ||
            matchedKeywords.any { it.patternType == "URL_PATTERN" && it.weight >= STRONG_URL_WEIGHT }
}
