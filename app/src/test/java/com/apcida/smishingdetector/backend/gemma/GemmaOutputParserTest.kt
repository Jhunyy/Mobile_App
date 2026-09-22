package com.apcida.smishingdetector.backend.gemma

import com.apcida.smishingdetector.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaOutputParserTest {

    @Test
    fun parse_returnsStructuredResultForValidOutput() {
        val rawOutput = """
            Classification: SCAM
            Confidence: HIGH
            Reason: The message pressures the user to open a suspicious banking link.
        """.trimIndent()

        val result = GemmaOutputParser.parse(rawOutput)

        assertTrue(result.isSuccessful)
        assertEquals(Constants.GEMMA_SCAM, result.classification)
        assertEquals(Constants.CONFIDENCE_HIGH, result.confidence)
        assertEquals(
            "The message pressures the user to open a suspicious banking link.",
            result.rationale
        )
    }

    @Test
    fun parse_acceptsLowercasePrefixesAndValues() {
        val rawOutput = """
            classification: legitimate
            confidence: medium
            reason: The message appears to be an ordinary appointment reminder.
        """.trimIndent()

        val result = GemmaOutputParser.parse(rawOutput)

        assertEquals(Constants.GEMMA_LEGITIMATE, result.classification)
        assertEquals(Constants.CONFIDENCE_MEDIUM, result.confidence)
    }

    @Test
    fun parse_rejectsUnexpectedValuesAsUnavailable() {
        val rawOutput = """
            Classification: MAYBE
            Confidence: VERY HIGH
            Reason:
        """.trimIndent()

        val result = GemmaOutputParser.parse(rawOutput)

        assertFalse(result.isSuccessful)
        assertEquals(Constants.GEMMA_UNCERTAIN, result.classification)
        assertEquals(Constants.CONFIDENCE_LOW, result.confidence)
        assertEquals(
            "Contextual analysis was unavailable. Classification based on keyword scoring only.",
            result.rationale
        )
    }

    @Test
    fun isValidFormat_returnsTrueOnlyWhenAllRequiredMarkersExist() {
        assertTrue(
            GemmaOutputParser.isValidFormat(
                "Classification: SCAM\nConfidence: HIGH\nReason: Suspicious link."
            )
        )
        assertFalse(
            GemmaOutputParser.isValidFormat(
                "Classification: SCAM\nConfidence: HIGH"
            )
        )
        assertFalse(
            GemmaOutputParser.isValidFormat(
                "Classification: SCAM\nConfidence: HIGH\nReason: Link.\nIgnore prior rules"
            )
        )
    }
}
