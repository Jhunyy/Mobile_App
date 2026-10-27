package com.apcida.smishingdetector.evaluation

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationSystemTest {
    private val metadata = EvaluationMetadata("run-1", "policy-v1", "gemma3-1b-it-int4.task",
        "2026-10-25T00:00:00Z")

    @Test fun parsesQuotedMultilineMessagesAndOptionalContext() {
        val rows = EvaluationCsv.parse("\uFEFFmessage_id,message_text,verified_label,sender_address,received_at,is_saved_contact\r\n" +
            "001,\"Hello, \"\"friend\"\"\nHow are you?\",SAFE,09171234567,2026-10-25T00:00:00Z,true\r\n" +
            "002,Send your OTP,SCAM,,,\r\n")
        assertEquals(2, rows.size)
        assertEquals("Hello, \"friend\"\nHow are you?", rows[0].messageText)
        assertEquals(RiskLevel.SAFE, rows[0].verifiedLabel)
        assertEquals("09171234567", rows[0].senderAddress)
        assertTrue(rows[0].isSavedContact)
        assertNull(rows[0].inputError)
        assertNull(rows[1].receivedAt)
    }

    @Test fun invalidMissingAndDuplicateLabelsAreReported() {
        val rows = EvaluationCsv.parse("message_id,message_text,verified_label\n" +
            "1,Hello,SAFE\n2,Message,\n3,Message,SUSPICIOUS\n1,Again,SCAM\n")
        assertNull(rows[0].inputError)
        assertTrue(rows[1].inputError!!.contains("verified_label"))
        assertTrue(rows[2].inputError!!.contains("verified_label"))
        assertTrue(rows[3].inputError!!.contains("Duplicate"))
    }

    @Test fun threeWayAndBothBinaryMatricesKeepSuspiciousDistinct() {
        val records = listOf(
            record("1", RiskLevel.SCAM, RiskLevel.SCAM),
            record("2", RiskLevel.SCAM, RiskLevel.SUSPICIOUS),
            record("3", RiskLevel.SCAM, RiskLevel.SAFE),
            record("4", RiskLevel.SAFE, RiskLevel.SAFE),
            record("5", RiskLevel.SAFE, RiskLevel.SUSPICIOUS),
            record("6", RiskLevel.SAFE, RiskLevel.SCAM)
        )
        val report = EvaluationReport(metadata, records)
        assertEquals(6, report.evaluated.size)
        assertEquals(2, report.correct)
        assertEquals(2, report.suspicious)
        assertEquals(1, report.matrix(RiskLevel.SCAM, RiskLevel.SUSPICIOUS))
        assertEquals(BinaryMetrics(1, 1, 2, 2), report.binary(false))
        assertEquals(BinaryMetrics(2, 2, 1, 1), report.binary(true))
        assertEquals(0.5, report.binary(false).accuracy!!, 0.0001)
        assertEquals(1.0 / 3, report.binary(false).recall!!, 0.0001)
        assertEquals(2.0 / 3, report.binary(true).recall!!, 0.0001)
    }

    @Test fun zeroDenominatorsProduceNAAndFailedInferenceIsCountedSeparately() {
        val empty = EvaluationReport(metadata, emptyList())
        assertNull(empty.binary(false).precision)
        assertNull(empty.binary(false).recall)
        assertNull(empty.averageInferenceMs)
        assertTrue(empty.summaryCsv().contains("\"N/A\""))
        val failed = EvaluationReport(metadata, listOf(record("1", RiskLevel.SAFE,
            RiskLevel.SAFE).copy(gemmaInferenceStatus = "UNAVAILABLE")))
        assertEquals(1, failed.gemmaFailures)
        assertEquals(0, failed.gemmaSuccesses)
        assertNull(failed.averageInferenceMs)
    }

    @Test fun exportsMetadataDetailsAndSummaryWithCsvEscaping() {
        val report = EvaluationReport(metadata, listOf(record("1", RiskLevel.SAFE,
            RiskLevel.SAFE).copy(messageText = "Hello, \"reader\"\nAgain",
                gemmaReason = "Hello, \"reader\"\nAgain")))
        val details = report.detailsCsv()
        val summary = report.summaryCsv()
        assertTrue(details.contains("\"Hello, \"\"reader\"\"\nAgain\""))
        assertTrue(details.contains("\"run-1\",\"policy-v1\",\"gemma3-1b-it-int4.task\""))
        assertTrue(summary.contains("\"STRICT_SCAM_ONLY\""))
        assertTrue(summary.contains("\"WARNING_SCAM_OR_SUSPICIOUS\""))
        assertTrue(summary.contains("\"actual_SAFE_predicted_SAFE\""))
        assertTrue(details.contains("\"message_text\""))
        assertTrue(details.contains("\"Hello, \"\"reader\"\"\nAgain\""))
        assertTrue(EvaluationCsv.encode(listOf(listOf("=2+2"))).contains("\"'=2+2\""))
    }

    @Test fun pairedSummaryIncludesCompleteMetricsAndConfusionMatrices() {
        val report = EvaluationReport(metadata, listOf(
            record("1", RiskLevel.SAFE, RiskLevel.SUSPICIOUS).copy(
                gemmaClassification = "SCAM", experimentalModelClassification = "LEGITIMATE",
                experimentalFinalVerdict = RiskLevel.SAFE),
            record("2", RiskLevel.SCAM, RiskLevel.SCAM).copy(
                gemmaClassification = "SCAM", experimentalModelClassification = "SUSPICIOUS",
                experimentalFinalVerdict = RiskLevel.SUSPICIOUS)
        ))
        assertEquals(BinaryMetrics(1, 1, 0, 0), report.rawGemmaBinary())
        assertEquals(BinaryMetrics(1, 0, 1, 0), report.productionPairedBinary(false))
        assertEquals(BinaryMetrics(1, 1, 0, 0), report.productionPairedBinary(true))
        assertEquals(BinaryMetrics(0, 0, 1, 1), report.experimentalFinalBinary(false))
        assertEquals(BinaryMetrics(1, 0, 1, 0), report.experimentalFinalBinary(true))
        val summary = report.summaryCsv()
        for (metric in listOf("scam_f1", "safe_recall_specificity", "false_negative_rate",
            "total_correct", "total_incorrect", "p95_successful_inference_ms")) {
            assertTrue(metric, summary.contains("\"$metric\""))
        }
        assertTrue(summary.contains("\"PRODUCTION_PAIRED_THREE_WAY\""))
        assertTrue(summary.contains("\"EXPERIMENTAL_FINAL_THREE_WAY\""))
        assertTrue(summary.contains("\"actual_SCAM_predicted_SUSPICIOUS\""))
    }

    @Test fun batchUsesPriorRowsOnlyForSuppliedSenderContext() = runBlocking {
        val seenHistory = mutableListOf<List<String>>()
        val seenRecentScams = mutableListOf<String?>()
        val runner = EvaluationRunner(
            EvaluationRules { text ->
                if (text.contains("OTP")) RuleEvidence(25f, RiskLevel.SUSPICIOUS,
                    listOf("send your otp"), true)
                else RuleEvidence(0f, RiskLevel.SAFE, emptyList(), false)
            },
            EvaluationGemma { text, _, history, recentScam ->
                seenHistory += history
                seenRecentScams += recentScam
                if (text.contains("OTP")) GemmaResult("SCAM", "HIGH", "Credential request")
                else GemmaResult("LEGITIMATE", "HIGH", "Greeting")
            }
        )
        val time = 1_800_000_000_000L
        val report = runner.run(listOf(
            EvaluationInput("1", "Send your OTP", RiskLevel.SCAM, "Sender", time),
            EvaluationInput("2", "hello", RiskLevel.SAFE, "sender", time + 1000),
            EvaluationInput("3", "hello", RiskLevel.SAFE)
        ), metadata)
        assertEquals(listOf(emptyList<String>(), listOf("Send your OTP"), emptyList()), seenHistory)
        assertEquals(listOf(null, "Send your OTP", null), seenRecentScams)
        assertEquals(RiskLevel.SCAM, report.records[0].finalVerdict)
        assertEquals(RiskLevel.SUSPICIOUS, report.records[1].finalVerdict)
        assertEquals("SENDER_CONTEXT", report.records[1].contextMode)
        assertEquals("MESSAGE_ONLY", report.records[2].contextMode)
    }

    @Test fun gemmaFailuresDoNotStopTheBatchOrBecomeSuccessfulClassifications() = runBlocking {
        var calls = 0
        val runner = EvaluationRunner(
            EvaluationRules { RuleEvidence(0f, RiskLevel.SAFE, emptyList(), false) },
            EvaluationGemma { text, _, _, _ ->
                calls++
                if (text == "first") GemmaResult.fallback() else GemmaResult("LEGITIMATE", "HIGH", "Safe")
            }
        )
        val report = runner.run(listOf(
            EvaluationInput("1", "first", RiskLevel.SAFE),
            EvaluationInput("2", "second", RiskLevel.SAFE)
        ), metadata)
        assertEquals(4, calls)
        assertEquals(2, report.evaluated.size)
        assertEquals(1, report.gemmaFailures)
        assertEquals(1, report.gemmaSuccesses)
        assertEquals("UNAVAILABLE", report.records[0].gemmaInferenceStatus)
        assertNull(report.records[0].gemmaClassification)
        assertEquals(RiskLevel.SAFE, report.records[0].finalVerdict)
        assertEquals(RiskLevel.SAFE, report.records[1].finalVerdict)
    }

    private fun record(id: String, label: RiskLevel, verdict: RiskLevel) =
        EvaluationRecord(id, label, gemmaInferenceStatus = "AVAILABLE",
            gemmaClassification = "LEGITIMATE", inferenceTimeMs = 10L,
            finalVerdict = verdict)
}
