package com.apcida.smishingdetector.backend.gemma

import com.apcida.smishingdetector.model.data.GemmaResult

object GemmaOutputParser {

    private val outputPattern = Regex(
        pattern = """\AClassification:\s*(SCAM|LEGITIMATE)\s*\RConfidence:\s*(HIGH|MEDIUM|LOW)\s*\RReason:\s*([^\r\n]{1,500})\s*\z""",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Parses Gemma's raw text output into a structured GemmaResult.
     *
     * Expected format:
     * Classification: SCAM or LEGITIMATE
     * Confidence: HIGH or MEDIUM or LOW
     * Reason: One sentence explanation
     *
     * If parsing fails, returns a fallback GemmaResult.
     */
    fun parse(rawOutput: String): GemmaResult {
        val match = outputPattern.matchEntire(rawOutput.trim()) ?: return GemmaResult.fallback(
            "Gemma returned malformed or unsupported output."
        )

        return GemmaResult(
            classification = match.groupValues[1].uppercase(),
            confidence = match.groupValues[2].uppercase(),
            rationale = match.groupValues[3].trim(),
            isSuccessful = true
        )
    }

    /**
     * Quick check to see if Gemma's raw output
     * contains the expected format markers.
     * Useful for detecting completely malformed responses.
     */
    fun isValidFormat(rawOutput: String): Boolean {
        return outputPattern.matches(rawOutput.trim())
    }
}
