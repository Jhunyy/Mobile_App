package com.apcida.smishingdetector.backend.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.dao.MessageKeywordDao
import com.apcida.smishingdetector.backend.repository.KeywordRepository
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.model.entity.Report
import com.apcida.smishingdetector.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ReportUploadManager(private val context: Context) {

    companion object {
        private const val TAG = "ReportUploadManager"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val database = AppDatabase.getInstance(context)
    private val reportRepository = ReportRepository(database.reportDao())
    private val keywordRepository = KeywordRepository(database.keywordDao())

    // Lazy initialization — only created when needed
    private val apiService: ReportApiService by lazy {
        createApiService()
    }

    /**
     * Checks for unsent reports and attempts to upload them
     * to the remote server if internet is available.
     *
     * Call this:
     * - On app startup
     * - When internet connectivity is restored
     * - After a new report is submitted
     */
    suspend fun uploadPendingReports() {
        withContext(Dispatchers.IO) {
            if (!isInternetAvailable()) {
                Log.d(TAG, "No internet connection. Skipping upload.")
                return@withContext
            }

            if (!isReportServerConfigured()) {
                Log.w(TAG, "Report server is not configured. Leaving reports pending.")
                return@withContext
            }

            val unsentReports = reportRepository.getUnsentReports()

            if (unsentReports.isEmpty()) {
                Log.d(TAG, "No pending reports to upload.")
                return@withContext
            }

            Log.d(TAG, "Found ${unsentReports.size} unsent report(s). Uploading...")

            for (report in unsentReports) {
                uploadSingleReport(report)
            }
        }
    }

    /**
     * Uploads a single report to the remote server.
     * Marks the report as sent on success.
     * Logs the error and leaves is_sent = false on failure
     * so it can be retried later.
     */
    private suspend fun uploadSingleReport(report: Report) {
        try {
            // Get keyword patterns matched for this message
            val matchedKeywords = getKeywordsForMessage(report.messageId)

            // Build anonymized payload — no raw SMS content included
            val payload = ReportPayload(
                reportType = report.reportType,
                remarks = report.remarks,
                riskScore = report.riskScoreSnapshot,
                gemmaClassification = report.gemmaClassificationSnapshot,
                gemmaRationale = report.gemmaRationaleSnapshot,
                detectedKeywords = matchedKeywords,
                reportedAt = report.reportedAt
            )

            val response = apiService.submitReport(
                apiKey = Constants.REPORT_API_KEY,
                timestamp = System.currentTimeMillis(),
                report = payload
            )

            if (response.isSuccessful) {
                reportRepository.markReportAsSent(report.reportId)
                Log.d(TAG, "Report ${report.reportId} uploaded successfully.")
            } else {
                Log.w(
                    TAG,
                    "Report ${report.reportId} upload failed. " +
                            "Server returned: ${response.code()} ${response.message()}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading report ${report.reportId}: ${e.message}")
            // Report stays as is_sent = false for retry on next attempt
        }
    }

    /**
     * Gets the keyword patterns matched for a specific message.
     * Used to include keyword context in the report payload.
     */
    private suspend fun getKeywordsForMessage(messageId: Long): List<String> {
        return try {
            val messageKeywords = database
                .messageKeywordDao()
                .getMatchesForMessage(messageId)

            messageKeywords.mapNotNull { mk ->
                mk.matchedText
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not fetch keywords for message $messageId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Checks if the device has an active internet connection.
     */
    fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager
            .getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }

    private fun isReportServerConfigured(): Boolean {
        return Constants.BASE_URL != "https://your-report-server.com/api/" &&
                Constants.REPORT_API_KEY.isNotBlank()
    }

    /**
     * Creates the Retrofit API service instance with
     * logging and timeout configuration.
     */
    private fun createApiService(): ReportApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ReportApiService::class.java)
    }
}
