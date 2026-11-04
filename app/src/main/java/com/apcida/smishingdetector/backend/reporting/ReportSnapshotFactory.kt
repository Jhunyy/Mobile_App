package com.apcida.smishingdetector.backend.reporting

import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.Report
import com.apcida.smishingdetector.util.Constants
import java.util.UUID

/** Captures the visible scan state once, before the report enters the upload queue. */
object ReportSnapshotFactory {
    const val REVIEW_AND_RAG_CANDIDATE = "REVIEW_AND_RAG_CANDIDATE"

    fun hasCombinedConsent(report: Report): Boolean =
        report.consentScope == REVIEW_AND_RAG_CANDIDATE &&
            report.smsTextSnapshot?.let { it.isNotBlank() && it.length <= 4000 } == true

    fun create(
        message: Message,
        reportType: String,
        remarks: String?,
        consentGranted: Boolean,
        appVersion: String,
        reportedAt: Long = System.currentTimeMillis(),
        clientReportId: String = UUID.randomUUID().toString()
    ): Report {
        require(consentGranted && message.content.isNotBlank() && message.content.length <= 4000) {
            "SMS text and combined report consent are required"
        }
        return Report(
        messageId = message.messageId,
        clientReportId = clientReportId,
        reportType = reportType,
        remarks = remarks,
        riskScoreSnapshot = message.riskScore,
        gemmaClassificationSnapshot = message.gemmaClassification,
        gemmaRationaleSnapshot = message.gemmaRationale,
        gemmaConfidenceSnapshot = message.gemmaConfidence,
        finalVerdictSnapshot = message.finalClassification,
        ruleVerdictSnapshot = message.deterministicRiskLevel,
        processingStateSnapshot = message.processingState,
        aiStatusSnapshot = message.aiAnalysisStatus,
        matchedIndicatorsSnapshot = message.matchedIndicators,
        smsTextSnapshot = message.content,
        consentScope = REVIEW_AND_RAG_CANDIDATE,
        appVersionSnapshot = appVersion,
        modelVersionSnapshot = when {
            message.verdictSource == "REMOTE_GEMMA_RAG" -> "gemma3:12b+RAG"
            message.gemmaInvoked -> Constants.GEMMA_MODEL_PATH.substringAfterLast('/') // legacy row
            message.verdictSource == "LOCAL_RULES" -> "keyword-rules-v2"
            else -> null
        },
        reportedAt = reportedAt
        )
    }
}
