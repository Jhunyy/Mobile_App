package com.apcida.smishingdetector.backend.community

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants
import java.security.MessageDigest

/** Local candidate filtering for collection, not a source of accuracy ground truth. */
object CommunityPolicy {
    const val CATEGORY_LIMIT = 20
    const val TOTAL_LIMIT = 40
    const val MAX_MESSAGE_LENGTH = 4000

    private val authentication = Regex("(?iu)\\b(?:otp|one.time|verification|verify|authentication|2fa|password|passcode|pin|cvv|security code|login code)\\b")
    private val shortCode = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val longNumber = Regex("(?<!\\d)\\d{9,}(?!\\d)")
    private val separatedNumber = Regex("(?<!\\d)(?:\\d[\\s().-]?){8,}\\d(?!\\d)")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val url = Regex("(?i)(?:https?://|www\\.)\\S+")
    private val phone = Regex("(?<!\\d)(?:\\+?63|0)9\\d{9}(?!\\d)")

    fun eligible(body: String): Boolean = body.isNotBlank() &&
        body.length <= MAX_MESSAGE_LENGTH &&
        !authentication.containsMatchIn(body) &&
        !shortCode.containsMatchIn(body) &&
        !longNumber.containsMatchIn(body) &&
        !separatedNumber.containsMatchIn(body)

    fun redact(body: String): String = phone.replace(
        separatedNumber.replace(longNumber.replace(email.replace(url.replace(body, "[link]"), "[email]"), "[number]"), "[number]"),
        "[phone]"
    ).trim()

    fun fingerprint(body: String): String = sha256(body.trim().lowercase().replace(Regex("\\s+"), " "))

    fun template(body: String): String = body.lowercase()
        .replace(url, "[link]")
        .replace(Regex("\\d+"), "#")
        .replace(Regex("\\s+"), " ").trim()

    fun remaining(accepted: Int): Int = (CATEGORY_LIMIT - accepted).coerceIn(0, CATEGORY_LIMIT)

    fun candidateCategory(ruleLevel: RiskLevel, gemma: GemmaResult, verdict: RiskLevel,
                          scamRemaining: Int, safeRemaining: Int): String? {
        if (!gemma.isSuccessful || gemma.confidence != Constants.CONFIDENCE_HIGH) return null
        return when {
            gemma.classification == Constants.GEMMA_SCAM && verdict == RiskLevel.SCAM && scamRemaining > 0 -> "SCAM"
            gemma.classification == Constants.GEMMA_LEGITIMATE && ruleLevel == RiskLevel.SAFE &&
                verdict == RiskLevel.SAFE && safeRemaining > 0 -> "SAFE"
            else -> null
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
