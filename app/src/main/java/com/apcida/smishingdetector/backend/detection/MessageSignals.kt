package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword

/** Broad, inspectable signals used by verdict policies. */
object MessageSignals {
    private const val STRONG_URL_WEIGHT = 30f

    private val credentialRequest = Regex(
        "(?iu)\\b(?:send|share|provide|enter|reply with|ibigay|ipadala|ilagay|ihatag|isulod)\\b.{0,50}\\b(?:otp|pin|password|cvv|verification code)\\b"
    )
    private val paymentRequest = Regex(
        "(?iu)\\b(?:send|pay|paying|transfer|deposit|magbayad|bayaran|bayri|ipadala)\\b.{0,50}\\b(?:money|cash|funds|fee|payment|pesos|php|bank account)\\b"
    )

    fun hasHighRiskRequest(message: String): Boolean =
        credentialRequest.containsMatchIn(message) || paymentRequest.containsMatchIn(message)

    fun hasStrongRuleSignal(message: String, matchedKeywords: List<Keyword>): Boolean =
        hasHighRiskRequest(message) ||
            matchedKeywords.any { it.patternType == "URL_PATTERN" &&
                RuleWeightPolicy.weight(it) >= STRONG_URL_WEIGHT }

    private val actionableRequest = Regex(
        "(?iu)\\b(?:send|share|provide|enter|reply|pay|transfer|deposit|click|open|visit|claim|verify|login|log in|call|text|download|register|ibigay|ipadala|ilagay|bayaran|bayri|ihatag|isulod|ablihi|adto|i-click|i-verify)\\b"
    )
    private val fraudContext = Regex(
        "(?iu)\\b(?:otp|pin|password|cvv|account|bank|money|cash|payment|fee|prize|premyo|reward|urgent|locked|suspended|verify|link|loan|investment|parcel|package)\\b"
    )

    /** Narrow neutral-text check; longer or actionable messages keep a review warning. */
    fun isLowEvidenceConversation(message: String, ruleScore: Float,
                                  hasStrongRuleSignal: Boolean, senderPoints: Int): Boolean =
        message.trim().split(Regex("\\s+")).size <= 12 && ruleScore == 0f &&
            !hasStrongRuleSignal && senderPoints == 0 &&
            UrlCandidates.extract(message).isEmpty() &&
            !actionableRequest.containsMatchIn(message) &&
            !fraudContext.containsMatchIn(message)
}
