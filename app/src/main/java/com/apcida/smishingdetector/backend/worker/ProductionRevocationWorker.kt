package com.apcida.smishingdetector.backend.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import com.apcida.smishingdetector.backend.network.ProductionRevocationClient
import com.apcida.smishingdetector.backend.network.ProductionRevocationRetryPolicy
import java.util.concurrent.TimeUnit

/** Deletion-only work. It cannot access SMS, Room message content, or the inference route. */
class ProductionRevocationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    companion object {
        private const val UNIQUE_NAME = "production_installation_revocation"

        fun enqueueIfPending(context: Context, replace: Boolean = false) {
            val app = context.applicationContext
            val consent = AiProtectionConsentStore(app)
            if (!consent.remoteDeletionPending()) return
            if (!replace && consent.remoteDeletionStatus() in setOf(
                    AiProtectionConsentStore.RemoteDeletionStatus.RETRY_PAUSED,
                    AiProtectionConsentStore.RemoteDeletionStatus.AUTH_REJECTED,
                    AiProtectionConsentStore.RemoteDeletionStatus.CREDENTIAL_UNAVAILABLE)) return
            val host = BuildConfig.PRODUCTION_APPROVED_HOST
            if (!ProductionEndpointPolicy.isApprovedHttpsOrigin("https://$host", host) ||
                host.endsWith(".trycloudflare.com", ignoreCase = true)) {
                AiProtectionConsentStore(app).markRemoteDeletionStatus(
                    AiProtectionConsentStore.RemoteDeletionStatus.CONFIGURATION_REQUIRED)
                return
            }
            val work = OneTimeWorkRequestBuilder<ProductionRevocationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        }
    }

    override suspend fun doWork(): Result {
        val consent = AiProtectionConsentStore(applicationContext)
        if (!consent.remoteDeletionPending()) return Result.success()
        val host = BuildConfig.PRODUCTION_APPROVED_HOST
        val origin = "https://$host"
        if (!ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, host) ||
            host.endsWith(".trycloudflare.com", ignoreCase = true)) {
            consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.CONFIGURATION_REQUIRED)
            return Result.success()
        }
        val credentials = ProductionCredentialStore(applicationContext)
        val token = credentials.getForRevocation()
        if (token == null) {
            consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.CREDENTIAL_UNAVAILABLE)
            return Result.success()
        }
        return when (ProductionRevocationClient(
            accessCredentials = CloudflareAccessCredentialStore(applicationContext)
        ).revoke(origin, host, token)) {
            ProductionRevocationClient.Result.CONFIRMED -> {
                // The backend accepted deletion; keep the local marker pending if
                // credential cleanup cannot be persisted.
                try {
                    credentials.clear()
                    consent.markRemoteDeletionConfirmed()
                } catch (_: Exception) {
                    consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.RETRY_PAUSED)
                }
                Result.success()
            }
            ProductionRevocationClient.Result.AUTH_REJECTED -> {
                // A proxy may have rejected this request before it reached the backend.
                // Retain the encrypted token for an administrator-assisted retry.
                consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.AUTH_REJECTED)
                Result.success()
            }
            ProductionRevocationClient.Result.CONFIGURATION_REQUIRED -> {
                consent.markRemoteDeletionStatus(
                    AiProtectionConsentStore.RemoteDeletionStatus.CONFIGURATION_REQUIRED)
                Result.success()
            }
            ProductionRevocationClient.Result.RETRYABLE_FAILURE -> {
                if (ProductionRevocationRetryPolicy.shouldRetry(runAttemptCount)) {
                    consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.PENDING)
                    Result.retry()
                } else {
                    consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.RETRY_PAUSED)
                    Result.success()
                }
            }
        }
    }
}
