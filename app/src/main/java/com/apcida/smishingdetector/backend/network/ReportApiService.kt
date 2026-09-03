package com.apcida.smishingdetector.backend.network

import com.apcida.smishingdetector.util.Constants
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit interface for submitting anonymized
 * detection reports to the remote server.
 *
 * Only anonymized metadata is transmitted —
 * no raw SMS content is ever sent.
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
 * Anonymized report data sent to the remote server.
 * Contains only detection metadata — no raw SMS content.
 */
data class ReportPayload(
    val reportType: String,
    val remarks: String?,
    val riskScore: Float?,
    val gemmaClassification: String?,
    val gemmaRationale: String?,
    val detectedKeywords: List<String>,
    val reportedAt: Long,
    val appVersion: String = "1.0"
)

/**
 * Server response after report submission.
 */
data class ReportResponse(
    val success: Boolean,
    val message: String?,
    val reportId: String?
)
