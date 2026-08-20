package com.apcida.smishingdetector.backend.gemma

object PromptBuilder {

    /**
     * Builds a structured prompt for Gemma to classify
     * a flagged SMS message as SCAM or LEGITIMATE.
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

SMS Message:
"$messageBody"

Keywords already detected by the keyword engine:
[$keywordList]

These detected keywords indicate potential scam patterns.
Use them as context but evaluate the full message meaning.

Respond ONLY in this exact format with no additional text:
Classification: [SCAM or LEGITIMATE]
Confidence: [HIGH or MEDIUM or LOW]
Reason: [One sentence explanation in plain language]
        """.trimIndent()
    }
}