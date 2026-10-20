package com.apcida.smishingdetector.backend.network

import com.apcida.smishingdetector.util.Constants
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit interface for submitting feedback and a scan snapshot.
 * SMS text is sent only when the user opts in.
 */
interface ReportApiService {

    @POST(Constants.REPORT_ENDPOINT)
    suspend fun submitReport(
        @Header(Constants.REPORT_API_KEY_HEADER) apiKey: String,
        @Header(Constants.REPORT_TIMESTAMP_HEADER) timestamp: Long,
        @Body report: ReportPayload
    ): Response<ReportResponse>
}

/**
 * Submission-time scan snapshot and optional user-approved SMS text.
 */
data class ReportPayload(
    val reportType: String,
    val remarks: String?,
    val riskScore: Float?,
    val gemmaClassification: String?,
    val gemmaRationale: String?,
    val detectedKeywords: List<String>,
    val reportedAt: Long,
    val appVersion: String,
    val clientReportId: String?,
    val finalVerdict: String?,
    val ruleVerdict: String?,
    val processingState: String?,
    val aiStatus: String?,
    val gemmaConfidence: String?,
    val modelVersion: String?,
    val smsText: String?,
    val messageSharingConsent: Boolean?
)

/**
 * Server response after report submission.
 */
data class ReportResponse(
    val success: Boolean,
    val message: String?,
    val reportId: String?
)
