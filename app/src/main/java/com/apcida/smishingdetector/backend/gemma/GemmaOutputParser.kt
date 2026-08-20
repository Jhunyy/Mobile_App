package com.apcida.smishingdetector.backend.gemma

import android.util.Log
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.util.Constants

object GemmaOutputParser {

    private const val TAG = "GemmaOutputParser"

    // Expected output line prefixes
    private const val PREFIX_CLASSIFICATION = "classification:"
    private const val PREFIX_CONFIDENCE = "confidence:"
    private const val PREFIX_REASON = "reason:"

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
        return try {
            Log.d(TAG, "Parsing Gemma output: $rawOutput")

            val lines = rawOutput
                .trim()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var classification = ""
            var confidence = ""
            var reason = ""

            for (line in lines) {
                val lowercaseLine = line.lowercase()
                when {
                    lowercaseLine.startsWith(PREFIX_CLASSIFICATION) -> {
                        classification = line
                            .substringAfter(":")
                            .trim()
                            .uppercase()
                    }
                    lowercaseLine.startsWith(PREFIX_CONFIDENCE) -> {
                        confidence = line
                            .substringAfter(":")
                            .trim()
                            .uppercase()
                    }
                    lowercaseLine.startsWith(PREFIX_REASON) -> {
                        reason = line
                            .substringAfter(":")
                            .trim()
                    }
                }
            }

            // Validate classification value
            val validClassification = when (classification) {
                Constants.GEMMA_SCAM -> Constants.GEMMA_SCAM
                Constants.GEMMA_LEGITIMATE -> Constants.GEMMA_LEGITIMATE
                else -> {
                    Log.w(TAG, "Unexpected classification value: $classification. Defaulting to UNCERTAIN.")
                    Constants.GEMMA_UNCERTAIN
                }
            }

            // Validate confidence value
            val validConfidence = when (confidence) {
                Constants.CONFIDENCE_HIGH -> Constants.CONFIDENCE_HIGH
                Constants.CONFIDENCE_MEDIUM -> Constants.CONFIDENCE_MEDIUM
                Constants.CONFIDENCE_LOW -> Constants.CONFIDENCE_LOW
                else -> {
                    Log.w(TAG, "Unexpected confidence value: $confidence. Defaulting to LOW.")
                    Constants.CONFIDENCE_LOW
                }
            }

            // Use fallback reason if empty
            val finalReason = reason.ifEmpty {
                "No explanation was provided by the contextual analysis."
            }

            Log.d(TAG, "Parsed — Classification: $validClassification | Confidence: $validConfidence")

            GemmaResult(
                classification = validClassification,
                confidence = validConfidence,
                rationale = finalReason,
                isSuccessful = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemma output: ${e.message}")
            GemmaResult.fallback()
        }
    }

    /**
     * Quick check to see if Gemma's raw output
     * contains the expected format markers.
     * Useful for detecting completely malformed responses.
     */
    fun isValidFormat(rawOutput: String): Boolean {
        val lower = rawOutput.lowercase()
        return lower.contains(PREFIX_CLASSIFICATION) &&
                lower.contains(PREFIX_CONFIDENCE) &&
                lower.contains(PREFIX_REASON)
    }
}