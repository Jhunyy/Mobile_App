package com.apcida.smishingdetector.backend.community

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.DELETE

interface CommunityApi {
    @GET("api/v1/community/contributions/progress")
    suspend fun progress(
        @Header("Authorization") authorization: String,
        @Header("CF-Access-Client-Id") accessId: String,
        @Header("CF-Access-Client-Secret") accessSecret: String,
        @Query("participantId") participantId: String
    ): Response<ContributionProgress>

    @POST("api/v1/community/contributions")
    suspend fun submit(
        @Header("Authorization") authorization: String,
        @Header("CF-Access-Client-Id") accessId: String,
        @Header("CF-Access-Client-Secret") accessSecret: String,
        @Body batch: ContributionBatch
    ): Response<ContributionResponse>

    @DELETE("api/v1/community/contributions")
    suspend fun withdraw(
        @Header("Authorization") authorization: String,
        @Header("CF-Access-Client-Id") accessId: String,
        @Header("CF-Access-Client-Secret") accessSecret: String
    ): Response<Void>
}

data class ContributionProgress(val scamAccepted: Int, val safeAccepted: Int, val totalAccepted: Int)
data class ContributionItem(
    val clientId: String,
    val text: String,
    val localSuggestion: String,
    val reviewedCategory: String,
    val reviewedAt: Long
)
data class ContributionBatch(
    val batchId: String,
    val participantId: String,
    val submittedAt: Long,
    val consentScope: String,
    val examinedCount: Int,
    val offeredCount: Int,
    val selectionMethod: String,
    val items: List<ContributionItem>
)
data class ContributionResponse(
    val acceptedClientIds: List<String>,
    val rejectedClientIds: List<String>,
    val progress: ContributionProgress
)
