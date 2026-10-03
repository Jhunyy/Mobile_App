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

    /** Invalid or incomplete output must not be treated as a successful verdict. */
    fun parse(rawOutput: String): GemmaResult {
        return try {
            Log.d(TAG, "Parsing Gemma output")

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
                            .trim('[', ']', '*', '`')
                            .trim()
                            .uppercase()
                    }
                    lowercaseLine.startsWith(PREFIX_CONFIDENCE) -> {
                        confidence = line
                            .substringAfter(":")
                            .trim()
                            .trim('[', ']', '*', '`')
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

            if (classification !in setOf(Constants.GEMMA_SCAM, Constants.GEMMA_LEGITIMATE, Constants.GEMMA_UNCERTAIN) ||
                confidence !in setOf(Constants.CONFIDENCE_HIGH, Constants.CONFIDENCE_MEDIUM, Constants.CONFIDENCE_LOW) ||
                reason.isBlank()
            ) {
                Log.w(TAG, "Incomplete or invalid contextual analysis output.")
                return GemmaResult.fallback()
            }

            Log.d(TAG, "Parsed — Classification: $classification | Confidence: $confidence")

            GemmaResult(
                classification = classification,
                confidence = confidence,
                rationale = reason,
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
        val lines = rawOutput.trim().lines().map { it.trim().lowercase() }
        return lines.any { it.startsWith(PREFIX_CLASSIFICATION) } &&
                lines.any { it.startsWith(PREFIX_CONFIDENCE) } &&
                lines.any { it.startsWith(PREFIX_REASON) }
    }
}
