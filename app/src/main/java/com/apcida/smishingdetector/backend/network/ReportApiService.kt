package com.apcida.smishingdetector.backend.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** Feedback uses the same private installation and Cloudflare credentials as remote analysis. */
interface ReportApiService {
    @POST("api/v1/reports")
    suspend fun submitReport(
        @Header("Authorization") authorization: String,
        @Header("CF-Access-Client-Id") clientId: String,
        @Header("CF-Access-Client-Secret") clientSecret: String,
        @Body report: ReportPayload
    ): Response<ReportResponse>
}

/** The SMS is sent only after the combined review/RAG consent was checked. */
data class ReportPayload(
    val clientReportId: String,
    val reportType: String,
    val remarks: String?,
    val smsText: String,
    val consentScope: String,
    val observedVerdict: String?,
    val riskScore: Float?,
)

data class ReportResponse(
    val reportId: String?,
    val status: String?
)
