package com.apcida.smishingdetector.backend.detection

/** Broad, inspectable signals used only when a model verdict has no keyword support. */
object MessageSignals {
    private val destination = Regex(
        "(?i)(?<![@\\p{L}\\p{N}-])(?:https?://|www\\.)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:/[^\\s<>()]*)?"
    )
    private val requestedAction = Regex(
        "(?iu)(?<![\\p{L}\\p{N}])(?:click|tap|open|visit|reply|call this|call us|pay|send|transfer|provide|enter|share|claim|verify|download|install|ibigay|ipadala|bayaran|magbayad|pindutin|i-click)(?![\\p{L}\\p{N}])"
    )
    private val credentialRequest = Regex(
        "(?iu)\\b(?:send|share|provide|enter|reply with|ibigay|ipadala|ilagay)\\b.{0,50}\\b(?:otp|pin|password|cvv|verification code)\\b"
    )
    private val paymentRequest = Regex(
        "(?iu)\\b(?:send|pay|transfer|deposit|magbayad|bayaran|ipadala)\\b.{0,50}\\b(?:money|cash|funds|fee|payment|pesos|php|bank account)\\b"
    )

    fun hasActionableSignal(message: String): Boolean =
        destination.containsMatchIn(message) || requestedAction.containsMatchIn(message)

    fun hasHighRiskRequest(message: String): Boolean =
        credentialRequest.containsMatchIn(message) || paymentRequest.containsMatchIn(message)
}
