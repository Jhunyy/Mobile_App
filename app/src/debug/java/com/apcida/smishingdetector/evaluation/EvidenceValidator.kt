package com.apcida.smishingdetector.evaluation

import com.apcida.smishingdetector.backend.detection.UrlCandidates
import com.apcida.smishingdetector.model.data.GemmaResult

enum class EvidenceTier { STRONG_FRAUD, AMBIGUOUS, UNSUPPORTED, NOT_APPLICABLE }

data class EvidenceAssessment(
    val tier: EvidenceTier,
    val supportedCues: List<String>,
    val unsupportedReasonClaims: List<String>,
    val experimentalClassification: String
)

/**
 * Debug-only, deliberately narrow claim check. It is a research heuristic, not a fact checker.
 * A link, payment request, promotion, brand, or unknown sender alone is never strong fraud proof.
 */
object EvidenceValidator {
    private val otp = Regex("""\b(?:otp|one[ -]?time (?:pin|password|code)|verification code|security code|pin|password|cvv|bank details)\b""", RegexOption.IGNORE_CASE)
    private val askSecret = Regex("""\b(?:send|share|reply|give|provide|enter|input|type|submit|ibigay|ipadala|isend|ilagay|ihatag|ipad[aá]la|isulti)\b""", RegexOption.IGNORE_CASE)
    private val negatedSecret = Regex("""\b(?:do not|don't|never|huwag|wag|ayaw|dili|never)\s+(?:\w+\s+){0,3}(?:send|share|give|provide|enter|input|submit|ibigay|ipadala|isend|ilagay|ihatag)\b""", RegexOption.IGNORE_CASE)
    private val account = Regex("""\b(?:account|bank|wallet|gcash|maya|login|log[ -]?in|verification|verify|i-?verify|beripikahin|i-?beripika|i-?verify|i-?log[ -]?in)\b""", RegexOption.IGNORE_CASE)
    private val threat = Regex("""\b(?:blocked|suspended|locked|freeze|frozen|closed|terminated|ma-?block|masususpinde|ma-?suspend|ma-?lock|ma-?close)\b""", RegexOption.IGNORE_CASE)
    private val prize = Regex("""\b(?:won|winner|prize|raffle|reward|premyo|napanalunan|daog|nakadaog)\b""", RegexOption.IGNORE_CASE)
    private val payment = Regex("""\b(?:pay|payment|fee|deposit|transfer|send money|bayad|magbayad|mag-?bayad|padala(?:han)? ng pera|kwarta|pera)\b""", RegexOption.IGNORE_CASE)
    private val demand = Regex("""\b(?:must|need to|required|immediately|now|today|before|claim|unlock|release|para|aron|dapat|kailangan|kinahanglan)\b""", RegexOption.IGNORE_CASE)
    private val promotion = Regex("""\b(?:sale|discount|off|promo|offer|deal|free shipping|voucher)\b""", RegexOption.IGNORE_CASE)

    fun assess(message: String, gemma: GemmaResult): EvidenceAssessment {
        if (!gemma.isSuccessful || gemma.classification != "SCAM") {
            return EvidenceAssessment(EvidenceTier.NOT_APPLICABLE, emptyList(), emptyList(),
                gemma.classification)
        }
        val links = UrlCandidates.extract(message)
        val hasLink = links.isNotEmpty()
        val hasSecret = otp.containsMatchIn(message)
        val hasRequest = askSecret.containsMatchIn(message) && !negatedSecret.containsMatchIn(message)
        val hasAccount = account.containsMatchIn(message)
        val hasThreat = threat.containsMatchIn(message)
        val hasPrize = prize.containsMatchIn(message)
        val hasPayment = payment.containsMatchIn(message)
        val hasDemand = demand.containsMatchIn(message)
        val cues = mutableListOf<String>()
        // Requiring two connected cues limits unsupported conclusions from a single word.
        if (hasSecret && hasRequest) cues += "credential_or_otp_request"
        if (hasLink && hasSecret && hasRequest && hasAccount) cues += "credential_entry_link"
        if (hasPrize && hasPayment && hasDemand) cues += "prize_with_required_payment"
        if (hasThreat && hasPayment && hasDemand && hasAccount) cues += "account_threat_with_payment"
        // These are contextual clues, not definitive fraud by themselves.
        if (hasLink) cues += "link_present"
        if (hasPayment) cues += "payment_mentioned"
        if (hasPrize) cues += "prize_mentioned"
        if (promotion.containsMatchIn(message)) cues += "promotion_mentioned"
        if (hasAccount || hasThreat) cues += "account_or_threat_mentioned"

        val unsupported = mutableListOf<String>()
        val reason = gemma.rationale
        if (otp.containsMatchIn(reason) && !hasSecret) unsupported += "credential_claim"
        if (Regex("""\b(?:url|link|website|site)\b""", RegexOption.IGNORE_CASE).containsMatchIn(reason) && !hasLink)
            unsupported += "link_claim"
        if (payment.containsMatchIn(reason) && !hasPayment) unsupported += "payment_claim"
        if (prize.containsMatchIn(reason) && !hasPrize) unsupported += "prize_claim"
        if (threat.containsMatchIn(reason) && !hasThreat) unsupported += "account_threat_claim"
        val strong = cues.any { it in setOf("credential_or_otp_request", "credential_entry_link",
            "prize_with_required_payment", "account_threat_with_payment") }
        val tier = when {
            strong -> EvidenceTier.STRONG_FRAUD
            cues.isNotEmpty() -> EvidenceTier.AMBIGUOUS
            else -> EvidenceTier.UNSUPPORTED
        }
        return EvidenceAssessment(tier, cues, unsupported, when (tier) {
            EvidenceTier.STRONG_FRAUD -> "SCAM"
            EvidenceTier.AMBIGUOUS -> "SUSPICIOUS"
            EvidenceTier.UNSUPPORTED -> "LEGITIMATE"
            EvidenceTier.NOT_APPLICABLE -> gemma.classification
        })
    }
}
