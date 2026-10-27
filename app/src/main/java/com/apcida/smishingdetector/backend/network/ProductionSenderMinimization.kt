package com.apcida.smishingdetector.backend.network

/**
 * Candidate policy for a future privacy review. Not wired to ProductionSmsTransport:
 * changing the sender supplied to Gemma requires separate classifier validation.
 */
internal object ProductionSenderMinimization {
    fun candidate(sender: String): String {
        val value = sender.trim()
        val digits = value.count(Char::isDigit)
        return if (digits >= 7 && value.length <= 25 &&
            value.matches(Regex("""\+?[0-9\s().-]+"""))) "PHONE_NUMBER" else value
    }
}
