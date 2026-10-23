package com.apcida.smishingdetector.backend.community

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface CommunityApi {
    @GET("community/contributions/progress")
    suspend fun progress(
        @Header("X-API-Key") apiKey: String,
        @Query("participantId") participantId: String
    ): Response<ContributionProgress>

    @POST("community/contributions")
    suspend fun submit(
        @Header("X-API-Key") apiKey: String,
        @Body batch: ContributionBatch
    ): Response<ContributionResponse>
}

data class ContributionProgress(val scamAccepted: Int, val safeAccepted: Int)
data class ContributionItem(
    val clientId: String,
    val text: String,
    val predictedCategory: String,
    val reviewedCategory: String,
    val reviewedAt: Long
)
data class ContributionBatch(
    val batchId: String,
    val participantId: String,
    val submittedAt: Long,
    val items: List<ContributionItem>
)
data class ContributionResponse(
    val acceptedClientIds: List<String>,
    val rejectedClientIds: List<String>,
    val progress: ContributionProgress
)
