package com.apcida.smishingdetector.backend.gemma

object PromptBuilder {

    /**
     * Builds a structured prompt for Gemma to classify
     * an SMS message as SCAM or LEGITIMATE.
     *
     * The prompt includes:
     * - System instruction defining Gemma's role
     * - The full SMS message text
     * - The list of detected keywords from Stage 1
     * - Strict output format requirement
     */
    fun build(messageBody: String, matchedKeywords: List<String>): String {

        val keywordList = if (matchedKeywords.isNotEmpty()) {
            matchedKeywords.joinToString(", ") { "\"$it\"" }
        } else {
            "none"
        }

        return """
You are a smishing detection assistant for Filipino mobile users.
Analyze the SMS message below and determine if it is a smishing attempt.

Consider the following when analyzing:
- Urgency language (act now, expires today, limited time)
- Impersonation of banks, government agencies, or delivery services
- Requests for personal information, OTP, PIN, or passwords
- Suspicious or shortened links (bit.ly, tinyurl, unknown domains)
- Grammatical irregularities or unusual phrasing
- Emotional pressure tactics (fear, reward, threats)
- Filipino and Taglish phrasing patterns common in local scam messages

Classify as SCAM only when this SMS contains concrete evidence of deception,
an unsafe link or request, impersonation, or pressure to take a risky action.
A routine reminder or notice without such evidence is LEGITIMATE, even if it is
brief or does not include every detail. A scheduled time alone is not urgency.
Do not invent facts or assume a bank, link, or request that is not in the SMS.

SMS Message:
"$messageBody"

Keywords already detected by the keyword engine:
[$keywordList]

Detected keywords, if any, indicate potential scam patterns.
A message with no keyword matches still needs a full contextual analysis.
Use them as context but evaluate the full message meaning.

Respond with exactly three lines and no additional text.
Line 1: Write "Classification: " followed by exactly one value, SCAM or LEGITIMATE.
Line 2: Write "Confidence: " followed by exactly one value, HIGH, MEDIUM, or LOW.
Line 3: Write "Reason: " followed by one plain-language sentence based only on this SMS.
        """.trimIndent()
    }
}
