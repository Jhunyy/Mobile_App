package com.apcida.smishingdetector.backend.reporting

import com.apcida.smishingdetector.model.entity.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportSnapshotFactoryTest {
    private val message = Message(
        messageId = 7,
        senderAddress = "+639171234567",
        content = "Verify at https://example.test/login",
        receivedAt = 1000,
        riskScore = 60f,
        deterministicRiskLevel = "SCAM",
        finalClassification = "SUSPICIOUS",
        matchedIndicators = "verify account\nunknown link",
        gemmaInvoked = true,
        gemmaClassification = "LEGITIMATE",
        gemmaConfidence = "LOW",
        gemmaRationale = "Looks routine.",
        aiAnalysisStatus = "AVAILABLE",
        processingState = "COMPLETED"
    )

    @Test
    fun capturesAllEvidenceWithoutSmsUnlessSelected() {
        val report = ReportSnapshotFactory.create(
            message, "Legitimate message was flagged", "Expected safe",
            includeSmsText = false, appVersion = "1.2", reportedAt = 2000,
            clientReportId = "5a884d88-074f-4fa2-842c-a9122ee37901"
        )

        assertEquals("5a884d88-074f-4fa2-842c-a9122ee37901", report.clientReportId)
        assertEquals("SUSPICIOUS", report.finalVerdictSnapshot)
        assertEquals("SCAM", report.ruleVerdictSnapshot)
        assertEquals(60f, report.riskScoreSnapshot)
        assertEquals("COMPLETED", report.processingStateSnapshot)
        assertEquals("AVAILABLE", report.aiStatusSnapshot)
        assertEquals("LEGITIMATE", report.gemmaClassificationSnapshot)
        assertEquals("LOW", report.gemmaConfidenceSnapshot)
        assertEquals("Looks routine.", report.gemmaRationaleSnapshot)
        assertEquals("verify account\nunknown link", report.matchedIndicatorsSnapshot)
        assertEquals("1.2", report.appVersionSnapshot)
        assertEquals("gemma3-1b-it-int4.task", report.modelVersionSnapshot)
        assertNull(report.smsTextSnapshot)
    }

    @Test
    fun includesExactSmsOnlyWhenSelected() {
        val report = ReportSnapshotFactory.create(
            message, "Scam was not detected", null,
            includeSmsText = true, appVersion = "1.2"
        )
        assertEquals(message.content, report.smsTextSnapshot)
    }
}
