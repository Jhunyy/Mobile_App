package com.apcida.smishingdetector.backend.detection

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import com.apcida.smishingdetector.model.data.RiskLevel

/** Kotlin port of backend src/services/rules.js, RULES_VERSION=2. Keep weights and caps in sync. */
object BackendKeywordRules {
    const val VERSION = 2
    data class Indicator(val code: String, val category: String, val score: Int)
    data class Result(val score: Int, val phraseScore: Int, val urlScore: Int,
                      val classification: RiskLevel, val matchedIndicators: List<Indicator>)

    private fun re(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
    private fun has(text: String, pattern: String) = re(pattern).containsMatchIn(text)
    private fun near(text: String, left: String, right: String, distance: Int = 85): Boolean {
        val a = re(left).find(text)?.range?.first ?: return false
        val b = re(right).find(text)?.range?.first ?: return false
        return kotlin.math.abs(a - b) <= distance
    }
    private val urlPattern = re("""\b(?:https?://|www\.)[^\s<>"']+|\b[a-z0-9][a-z0-9.-]+\.[a-z]{2,12}(?:/[\w/?#%&=.+-]*)?""")
    private val secret = """\b(?:otp|one[- ]time (?:pin|password|code)|six[- ]digit (?:otp|code)|sms code|verification code|login code|recovery code|pin|password|passcode|cvv|security code|security answers?|login credentials?|banking credentials?|banking login|bank login|administrator login code|login|username|card details|atm pin)\b"""
    private val disclose = """\b(?:send|share|reply with|replying with|text back|reveal|disclose|provide|give|submit|ibigay|ipadala|isend|i-send|i-reply|ihatag|ipasa|isubmit|i-submit|ipakita)\b"""
    private val enter = """\b(?:enter|type|verify|update|confirm|ilagay|i-enter|i-type|i-verify|i-update|i-click|pindutin|i-tap)\b"""
    private val paymentVerb = """\b(?:pay|settle|deposit|transfer|send money|send funds|send it back|need money|buy|purchase|bayad|bayaran|magbayad|mobayad|bayri|hulog|maghulog|padala|ipadala|i-send|isend|magpadala)\b"""
    private val paymentObject = """\b(?:money|funds|cash|kwarta|pera|bayad|payment|fee|charge|deposit|tax|fine|multa|bill|wallet|account|gift cards?|php|piso)\b"""
    private val selfClaim = """\b(?:we are|this is|i am|i'm|from|official|representative|agent|kami|ako|taga|mula|gikan|kuno)\b"""
    private val authority = """\b(?:bank|bdo|bpi|metrobank|gcash|maya|e-wallet|wallet|mobile network|sim provider|telecom|police|court|government|gobyerno|ahensiya|bir|sss|philhealth|hr|manager|teacher|school|security team|support|customer service|tech support|refund desk|courier|parcel office|company|kompanya)\b"""
    private val problemNoun = """(?:account|e-wallet|banking|sim|subscription|payroll|deposit|profile|business listing|employment|enrollment|scholarship|refund|payment|card)"""
    private val problemWord = """(?:fail(?:ed|ure)?|expire[ds]?|suspend(?:ed)?|suspension|block(?:ed)?|lock(?:ed)?|remove[ds]?|cancel(?:led|ed|ation)?|charge[ds]?|forfeit(?:ed)?|reactivat(?:e|ion)|reversal|problem|problema|na-expire|makakansela|mawawala|ma-suspend)"""

    fun analyze(message: String): Result {
        val text = Normalizer.normalize(message, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val withoutWarnings = text.replace(re("""\b(?:do not|don't|never|huwag|wag|ayaw|dili)\b.{0,30}\b(?:share|send|ibigay|ipadala|ihatag|i-share|i-send)\b"""), " ")
        val found = mutableListOf<Indicator>()
        fun add(condition: Boolean, code: String, category: String, score: Int) {
            if (condition) found += Indicator(code, category, score)
        }
        val urls = urlPattern.findAll(message).map { it.value.trimEnd(')', ',', '.', '!', '?') }.toList()
        var urlScore = 0
        var urlCode: String? = null
        for (value in urls) {
            val raw = if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else "https://$value"
            val parsed = runCatching { URI(raw) }.getOrNull() ?: continue
            val host = parsed.host?.lowercase(Locale.ROOT) ?: continue
            val (score, code) = when {
                has(host, """^(?:\d{1,3}\.){3}\d{1,3}$""") || host.startsWith("xn--") ||
                    has(host, """\.(?:xyz|top|click|site)$""") -> 45 to "SUSPICIOUS_URL"
                has(host, """^(?:bit\.ly|tinyurl\.com|t\.co|cutt\.ly)$""") || parsed.userInfo != null || '@' in value -> 35 to "OBSCURED_URL"
                else -> 5 to "LINK_PRESENT"
            }
            if (score > urlScore) { urlScore = score; urlCode = code }
        }
        if (urlCode != null) found += Indicator(urlCode, "url", minOf(80, urlScore))

        val organizationNeedsSecret = has(withoutWarnings, """\b(?:we|bank|team|namin|namo|kami)\b.{0,25}\b(?:need|needs|kailangan|kinahanglan)\b.{0,45}\b(?:otp|pin|password|cvv|code|login)\b|\b(?:kailangan|kinahanglan)\b.{0,20}\b(?:namin|namo)\b.{0,45}\b(?:otp|pin|password|cvv|code|login)\b""")
        val secretDisclosure = near(withoutWarnings, disclose, secret) || organizationNeedsSecret ||
            (has(withoutWarnings, secret) && has(withoutWarnings, """\b(?:in this reply|sa reply|sa among agent|sa aming agent)\b"""))
        val credentialForm = !secretDisclosure && has(text, secret) && has(text, enter) &&
            (urls.isNotEmpty() || has(text, """\b(?:link|form|website|site|portal|claim|refund|reversal)\b"""))
        add(secretDisclosure, "CREDENTIAL_REQUEST", "credentials", 55)
        add(credentialForm, "CREDENTIAL_FORM_REQUEST", "credentials", 35)
        val identity = near(text, """\b(?:send|share|submit|provide|upload|ibigay|ipadala|i-submit|ihatag)\b""",
            """\b(?:personal id|valid id|id photo|identity document|government id)\b""")
        add(identity, "IDENTITY_DOCUMENT_REQUEST", "credentials", 35)
        val paymentAction = has(text, paymentVerb) && has(text, paymentObject)
        val personalDestination = has(text, """\b(?:personal|private|individual|unfamiliar|other|new|bag-ong|bagong|this)\b.{0,25}\b(?:wallet|account|number)|\b(?:wallet|account)\b.{0,25}\b(?:below|representative|agent)\b""")
        val newNumberMoney = paymentAction && has(text, """\b(?:family member|relative|mama|mother|father|sister|brother)\b""") &&
            has(text, """\bnew number\b""") && has(text, """\b(?:do not call|don't call|ayaw pagtawag)\b""")
        add(newNumberMoney, "NEW_NUMBER_MONEY_REQUEST", "payment", 35)
        add(paymentAction && personalDestination, "PERSONAL_PAYMENT_REQUEST", "payment", 35)
        val giftCard = has(text, """\b(?:gift cards?|prepaid cards?)\b""") && has(text, """\b(?:buy|purchase|send|codes?|bilhin|bumili|ipalit|palit)\b""")
        add(giftCard, "GIFT_CARD_REQUEST", "payment", 45)
        val advance = has(text, """\b(?:processing fee|release (?:fee|charge)|activation fee|registration fee|clearance deposit|upfront (?:fee|insurance)|advance (?:fee|processing|payment)|withdrawal tax|verification fee|recovery fee|handling fee|identity-check charge|bayad muna|magbayad muna|bayri una|deposit first|pay first)\b""") &&
            (paymentAction || has(text, """\b(?:first|before|muna|una|advance)\b"""))
        add(advance, "ADVANCE_FEE", "payment", 40)
        val delivery = has(text, """\b(?:package|parcel|delivery|shipment|courier|padala|pakete)\b""") &&
            has(text, """\b(?:fee|charge|customs|clearance|redelivery|bayad|bayaran|magbayad|mobayad|handling)\b""") && paymentAction
        add(delivery, "DELIVERY_FEE", "payment", 35)
        val appInstall = has(text, """\b(?:install|open|download|i-install|i-open|i-download|ablihi|i-klik)\b.{0,45}\b(?:apk|remote[- ]control (?:app|application)|remote access (?:app|application))\b|\bapk\b.{0,45}\b(?:install|open|download)\b""")
        add(appInstall, "UNSAFE_APP_INSTALL", "software", 45)
        val risky = secretDisclosure || credentialForm || identity || paymentAction || advance || giftCard || appInstall
        val authorityClaim = risky && has(text, authority) && (has(text, selfClaim) || has(text, """\b(?:bank alert|security team|hr notice|payroll update)\b"""))
        add(authorityClaim, "AUTHORITY_CLAIM", "claim", 20)
        val accountProblem = risky && has(text, """\b$problemNoun\b.{0,70}\b$problemWord\b|\b$problemWord\b.{0,70}\b$problemNoun\b""")
        add(accountProblem, "ACCOUNT_PROBLEM_CLAIM", "claim", 20)
        val familyEmergency = paymentAction && has(text, """\b(?:family|relative|mama|mother|father|sister|brother|anak|inahan|papa)\b""") &&
            has(text, """\b(?:injured|hospital|emergency|urgent|accident|new number|bag-ong account|bagong account)\b""")
        add(newNumberMoney, "FAMILY_IDENTITY_CLAIM", "claim", 25)
        add(familyEmergency, "FAMILY_EMERGENCY_CLAIM", "claim", 25)
        val recovery = risky && has(text, """\b(?:recover|recovery|lost|stolen|nawalang|naibalik|maibalik)\b.{0,45}\b(?:funds|money|pera|account)\b|\b(?:funds|money|pera)\b.{0,45}\b(?:recover|recovery|lost|stolen|nawalang|naibalik|maibalik)\b""")
        add(recovery, "RECOVERY_CLAIM", "claim", 25)
        val reversal = paymentAction && has(text, """\b(?:accidentally sent|sent money|wrong transfer|mistaken transfer|ibalik|refund)\b""") &&
            has(text, """\b(?:back|return|other account|ibang account|new account)\b""")
        add(reversal, "TRANSFER_REVERSAL_CLAIM", "claim", 25)
        val government = risky && has(text, """\b(?:court|police|traffic|tax|refund|multa|fine|government|gobyerno|bir|ayuda|cash assistance|financial assistance)\b""") &&
            has(text, """\b(?:fine|multa|settlement|tax|refund|charge|payment|bayad|bayaran|claim|approved|agent|officer|identity-check)\b""")
        add(government, "GOVERNMENT_PAYMENT_CLAIM", "claim", 25)
        val prize = risky && has(text, """\b(?:won|winner|prize|reward|cashback|lottery|jackpot|nanalo|panalo|premyo|nadaog|daog|gantimpala|ayuda|financial assistance|tabang pinansyal|tulong pinansyal|cash assistance|raffle)\b""")
        add(prize, "PRIZE_OR_AID_CLAIM", "claim", 25)
        val opportunity = risky && has(text, """\b(?:job|work from home|hiring|recruitment|trabaho|hanapbuhay|empleyo|panginabuhi|scholarship|enrollment|loan|housing loan|credit limit)\b""")
        add(opportunity, "JOB_OR_SCHOLARSHIP_CLAIM", "claim", 25)
        add(delivery && !government, "DELIVERY_CLAIM", "claim", 20)
        val urgency = has(text, """\b(?:arrest|legal action|account (?:will be )?(?:blocked|suspended|closed)|disconnect|terminated|penalty|fine|demanda|aresto|kakanselahin|putlon|masuspinde|ma-suspend|ma-block|forfeit|lost|removed|unless|or you will|now|today|immediately|within \d+ (?:hour|minute)|final notice|last chance|ngayon|agad|kaagad|karun|dayon|dali|before|bago|sa loob ng|ayaw na pagtawag|do not call)\b""")
        add(urgency && risky, "COERCIVE_URGENCY", "pressure", 20)

        val byCategory = linkedMapOf<String, Indicator>()
        found.forEach { if ((byCategory[it.category]?.score ?: -1) < it.score) byCategory[it.category] = it }
        val matched = byCategory.values.toList()
        val phrase = minOf(60, matched.filter { it.category != "url" }.sumOf { it.score })
        val url = minOf(80, matched.firstOrNull { it.category == "url" }?.score ?: 0)
        val score = phrase + url
        val classification = when { score >= 60 -> RiskLevel.SCAM; score >= 30 -> RiskLevel.SUSPICIOUS; else -> RiskLevel.SAFE }
        return Result(score, phrase, url, classification, matched)
    }
}
