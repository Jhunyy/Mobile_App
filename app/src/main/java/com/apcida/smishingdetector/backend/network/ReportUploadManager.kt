package com.apcida.smishingdetector.backend.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.model.entity.Report
import com.apcida.smishingdetector.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ReportUploadManager(private val context: Context) {

    companion object {
        private const val TAG = "ReportUploadManager"
        private const val TIMEOUT_SECONDS = 90L
    }

    private val database = AppDatabase.getInstance(context)
    private val reportRepository = ReportRepository(database.reportDao())

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
     * - After a new report is submitted
     */
    suspend fun uploadPendingReports(): Boolean = withContext(Dispatchers.IO) {
        val unsentReports = reportRepository.getUnsentReports()

        if (unsentReports.isEmpty()) {
            Log.d(TAG, "No pending reports to upload.")
            return@withContext true
        }

        if (!isInternetAvailable()) {
            Log.d(TAG, "No internet connection. Skipping upload.")
            return@withContext false
        }

        if (!isReportServerConfigured()) {
            Log.w(TAG, "Report server is not configured. Leaving reports pending.")
            return@withContext false
        }

        Log.d(TAG, "Found ${unsentReports.size} unsent report(s). Uploading...")

        for (report in unsentReports) {
            uploadSingleReport(report)
        }

        return@withContext reportRepository.getUnsentReportCount() == 0
    }

    /**
     * Uploads a single report to the remote server.
     * Marks the report as sent on success.
     * Logs the error and leaves is_sent = false on failure
     * so it can be retried later.
     */
    private suspend fun uploadSingleReport(report: Report) {
        try {
            val matchedKeywords = report.matchedIndicatorsSnapshot
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.take(30)
                ?.toList()
                ?: emptyList()

            // Every field comes from the local report snapshot, never the message row.
            val payload = ReportPayload(
                reportType = report.reportType,
                remarks = report.remarks,
                riskScore = report.riskScoreSnapshot,
                gemmaClassification = report.gemmaClassificationSnapshot,
                gemmaRationale = report.gemmaRationaleSnapshot,
                detectedKeywords = matchedKeywords,
                reportedAt = report.reportedAt,
                appVersion = report.appVersionSnapshot ?: BuildConfig.VERSION_NAME,
                clientReportId = report.clientReportId,
                finalVerdict = report.finalVerdictSnapshot,
                ruleVerdict = report.ruleVerdictSnapshot,
                processingState = report.processingStateSnapshot,
                aiStatus = report.aiStatusSnapshot,
                gemmaConfidence = report.gemmaConfidenceSnapshot,
                modelVersion = report.modelVersionSnapshot,
                smsText = report.smsTextSnapshot,
                messageSharingConsent = if (report.clientReportId != null) {
                    report.smsTextSnapshot != null
                } else null
            )

            val response = apiService.submitReport(
                apiKey = Constants.REPORT_API_KEY,
                timestamp = System.currentTimeMillis(),
                report = payload
            )

            if (response.isSuccessful &&
                response.body()?.success == true &&
                !response.body()?.reportId.isNullOrBlank()
            ) {
                reportRepository.markReportAsSent(report.reportId)
                Log.d(TAG, "Report ${report.reportId} uploaded successfully.")
            } else {
                Log.w(
                    TAG,
                    "Report ${report.reportId} upload failed. " +
                            "Server returned HTTP ${response.code()}"
                )
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Report ${report.reportId} upload failed; will retry.")
            // Report stays as is_sent = false for retry on next attempt
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
        return Constants.BASE_URL.startsWith("https://") &&
                Constants.BASE_URL.endsWith("/") &&
                Constants.REPORT_API_KEY.isNotBlank()
    }

    /**
     * Creates the Retrofit API service instance with
     * logging and timeout configuration.
     */
    private fun createApiService(): ReportApiService {
        val okHttpClient = OkHttpClient.Builder()
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
