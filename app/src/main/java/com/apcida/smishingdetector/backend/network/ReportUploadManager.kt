package com.apcida.smishingdetector.backend.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.backend.reporting.ReportUploadEligibility
import com.apcida.smishingdetector.model.entity.Report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uploads explicitly submitted reports using this phone's invite-issued credentials. */
class ReportUploadManager(private val context: Context) {
    companion object { private const val TAG = "ReportUploadManager" }

    private val app = context.applicationContext
    private val repository = ReportRepository(AppDatabase.getInstance(app).reportDao())

    suspend fun uploadPendingReports(): Boolean = withContext(Dispatchers.IO) {
        val pending = repository.getUnsentReports().filter {
            ReportUploadEligibility.isEligible(app, it.reportedAt)
        }
        if (pending.isEmpty()) return@withContext true
        if (!isInternetAvailable()) return@withContext false
        val host = BuildConfig.PRODUCTION_APPROVED_HOST
        val origin = "https://$host"
        if (!ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, host) ||
            host.endsWith(".trycloudflare.com", ignoreCase = true)) return@withContext false
        val backend = ProductionCredentialStore(app).get() ?: return@withContext false
        val access = CloudflareAccessCredentialStore(app).get() ?: return@withContext false
        val api = Retrofit.Builder().baseUrl("$origin/")
            .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build())
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(ReportApiService::class.java)

        for (report in pending) uploadOne(report, api, backend, access)
        repository.getUnsentReports().none { ReportUploadEligibility.isEligible(app, it.reportedAt) }
    }

    private suspend fun uploadOne(
        report: Report, api: ReportApiService, backend: String,
        access: CloudflareAccessCredential
    ) {
        val id = report.clientReportId?.takeIf {
            runCatching { UUID.fromString(it) }.isSuccess
        } ?: return // Old local reports without a durable UUID stay local.
        val sms = report.smsTextSnapshot?.takeIf { it.isNotEmpty() && it.length <= 4000 }
        val payload = ReportPayload(
            clientReportId = id,
            reportType = report.reportType,
            remarks = report.remarks?.take(500),
            smsText = sms,
            messageSharingConsent = sms != null,
            observedVerdict = report.finalVerdictSnapshot?.takeIf {
                it in setOf("SAFE", "SUSPICIOUS", "SCAM", "UNVERIFIED")
            },
            riskScore = report.riskScoreSnapshot?.takeIf { it.isFinite() && it in 0f..100f }
        )
        try {
            val response = api.submitReport("Bearer $backend", access.clientId,
                access.clientSecret, payload)
            if (response.code() in listOf(200, 201) &&
                response.body()?.reportId == id &&
                response.body()?.status == "RECEIVED_FOR_REVIEW") {
                repository.markReportAsSent(report.reportId)
            } else {
                Log.w(TAG, "Report upload returned HTTP ${response.code()}; pending for retry.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(TAG, "Report upload unavailable; pending for retry.")
        }
    }

    fun isInternetAvailable(): Boolean {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
