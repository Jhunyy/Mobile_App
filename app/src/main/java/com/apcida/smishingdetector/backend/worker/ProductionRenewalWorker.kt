package com.apcida.smishingdetector.backend.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import com.apcida.smishingdetector.backend.network.ProductionRenewalClient
import java.util.concurrent.TimeUnit

/** Credential maintenance only. This worker never reads or uploads SMS. */
class ProductionRenewalWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    companion object {
        private const val UNIQUE_NAME = "production_installation_renewal"
        private const val DAY_MS = 86_400_000L

        fun scheduleIfConfigured(context: Context) {
            val app = context.applicationContext
            if (AiProtectionConsentStore(app).remoteDeletionPending() ||
                !ProductionCredentialStore(app).hasStoredCredential() ||
                !CloudflareAccessCredentialStore(app).hasStoredCredential()) return
            val host = BuildConfig.PRODUCTION_APPROVED_HOST
            if (!ProductionEndpointPolicy.isApprovedHttpsOrigin("https://$host", host) ||
                host.endsWith(".trycloudflare.com", ignoreCase = true)) return
            val work = PeriodicWorkRequestBuilder<ProductionRenewalWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (AiProtectionConsentStore(app).remoteDeletionPending()) return Result.success()
        val backend = ProductionCredentialStore(app)
        val currentExpiry = backend.expiresAtMillis() ?: return Result.success()
        val bearer = backend.get() ?: return Result.success()
        val access = CloudflareAccessCredentialStore(app).get() ?: return Result.success()
        val now = System.currentTimeMillis()
        if (currentExpiry - now > 7 * DAY_MS) return Result.success()
        val renewed = ProductionRenewalClient().renew(BuildConfig.PRODUCTION_APPROVED_HOST,
            bearer, access) ?: return Result.retry()
        if (renewed <= currentExpiry || renewed > now + 30 * DAY_MS ||
            AiProtectionConsentStore(app).remoteDeletionPending()) return Result.retry()
        return try {
            backend.extendExpiry(renewed)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
