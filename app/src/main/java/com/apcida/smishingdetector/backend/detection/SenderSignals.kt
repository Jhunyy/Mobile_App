package com.apcida.smishingdetector.backend.detection

/** A small, inspectable mismatch signal. Contact-book absence carries no points. */
object SenderSignals {
    private val personalMobileNumber = Regex("^(?:\\+63|0)9\\d{9}$")
    private val institutionClaim = Regex(
        "(?iu)\\b(?:this is|from|mula sa|galing sa)\\s+(?:bdo|bpi|metrobank|gcash|maya)\\b"
    )

    fun points(senderAddress: String?, messageBody: String): Int {
        val normalized = senderAddress?.filterNot { it.isWhitespace() || it == '-' } ?: return 0
        return if (personalMobileNumber.matches(normalized) && institutionClaim.containsMatchIn(messageBody)) 5 else 0
    }
}
