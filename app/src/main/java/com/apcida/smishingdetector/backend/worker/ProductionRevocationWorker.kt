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
            val pending = consent.remoteDeletionPending()
            val serverConfirmed = consent.remoteDeletionStatus() ==
                AiProtectionConsentStore.RemoteDeletionStatus.CONFIRMED
            val localCleanup = serverConfirmed &&
                (ProductionCredentialStore(app).hasStoredCredential() ||
                    CloudflareAccessCredentialStore(app).hasStoredCredential())
            if (!pending && !localCleanup) return
            if (pending) ProductionRenewalWorker.cancel(app)
            if (pending && !serverConfirmed && !replace && consent.remoteDeletionStatus() in setOf(
                    AiProtectionConsentStore.RemoteDeletionStatus.RETRY_PAUSED,
                    AiProtectionConsentStore.RemoteDeletionStatus.AUTH_REJECTED,
                    AiProtectionConsentStore.RemoteDeletionStatus.CREDENTIAL_UNAVAILABLE)) return
            val host = BuildConfig.PRODUCTION_APPROVED_HOST
            if (pending && !serverConfirmed &&
                (!ProductionEndpointPolicy.isApprovedHttpsOrigin("https://$host", host) ||
                host.endsWith(".trycloudflare.com", ignoreCase = true))) {
                AiProtectionConsentStore(app).markRemoteDeletionStatus(
                    AiProtectionConsentStore.RemoteDeletionStatus.CONFIGURATION_REQUIRED)
                return
            }
            val work = OneTimeWorkRequestBuilder<ProductionRevocationWorker>()
                .apply { if (pending && !serverConfirmed) setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()) }
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        }
    }

    override suspend fun doWork(): Result {
        val consent = AiProtectionConsentStore(applicationContext)
        val credentials = ProductionCredentialStore(applicationContext)
        val access = CloudflareAccessCredentialStore(applicationContext)
        val serverConfirmed = consent.remoteDeletionStatus() ==
            AiProtectionConsentStore.RemoteDeletionStatus.CONFIRMED
        if (!consent.remoteDeletionPending() || serverConfirmed) {
            if (!consent.remoteDeletionPending() && !serverConfirmed)
                return Result.success()
            return try {
                if (consent.remoteDeletionPending()) consent.markRemoteDeletionConfirmed()
                credentials.clear()
                access.clear()
                Result.success()
            } catch (_: Exception) { Result.retry() }
        }
        val host = BuildConfig.PRODUCTION_APPROVED_HOST
        val origin = "https://$host"
        if (!ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, host) ||
            host.endsWith(".trycloudflare.com", ignoreCase = true)) {
            consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.CONFIGURATION_REQUIRED)
            return Result.success()
        }
        val token = credentials.getForRevocation()
        if (token == null) {
            consent.markRemoteDeletionStatus(AiProtectionConsentStore.RemoteDeletionStatus.CREDENTIAL_UNAVAILABLE)
            return Result.success()
        }
        return when (ProductionRevocationClient(
            accessCredentials = access
        ).revoke(origin, host, token)) {
            ProductionRevocationClient.Result.CONFIRMED -> {
                // Record server confirmation before clearing either local credential.
                // The next worker run can finish local cleanup without authenticating again.
                try {
                    consent.markRemoteDeletionStatus(
                        AiProtectionConsentStore.RemoteDeletionStatus.CONFIRMED)
                    consent.markRemoteDeletionConfirmed()
                    credentials.clear()
                    access.clear()
                } catch (_: Exception) {
                    return Result.retry()
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
