package com.apcida.smishingdetector.backend.enrollment

import android.content.Context
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredential
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialSource
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionRevocationClient
import com.apcida.smishingdetector.backend.worker.ProductionRenewalWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stores per-phone beta credentials without enabling automatic SMS analysis. */
internal class InviteEnrollmentService(context: Context) {
    companion object { private val enrollmentMutex = Mutex() }
    enum class Result { SET_UP, ALREADY_CONFIGURED, FAILED, OWNER_CLEANUP_REQUIRED }

    private val app = context.applicationContext

    suspend fun enroll(host: String, code: String): Result =
        withContext(NonCancellable + Dispatchers.IO) {
            enrollmentMutex.withLock {
                val backend = ProductionCredentialStore(app)
                val access = CloudflareAccessCredentialStore(app)
                if (AiProtectionConsentStore(app).remoteDeletionPending() ||
                    backend.hasStoredCredential() || access.hasStoredCredential()) {
                    return@withLock Result.ALREADY_CONFIGURED
                }
                val bundle = try {
                    InviteEnrollmentClient.redeem(host, code)
                } catch (_: Exception) {
                    return@withLock Result.FAILED
                }
                try {
                    backend.provision(bundle.installationToken, bundle.expiresAt)
                    access.provision(bundle.clientId, bundle.clientSecret)
                    runCatching { ProductionRenewalWorker.scheduleIfConfigured(app) }
                    return@withLock Result.SET_UP
                } catch (_: Exception) {
                    // A redeemed invite has created a server installation. Attempt to
                    // revoke it using the credentials still held only in memory.
                    val inMemoryAccess = CloudflareAccessCredentialSource {
                        CloudflareAccessCredential(bundle.clientId, bundle.clientSecret)
                    }
                    val revoked = try {
                        ProductionRevocationClient(accessCredentials = inMemoryAccess).revoke(
                            "https://$host", host, bundle.installationToken)
                    } catch (_: Exception) {
                        ProductionRevocationClient.Result.RETRYABLE_FAILURE
                    }
                    if (revoked == ProductionRevocationClient.Result.CONFIRMED) {
                        try {
                            access.clear()
                            backend.clear()
                            return@withLock Result.FAILED
                        } catch (_: Exception) { /* Keep the incomplete state visible. */ }
                    }
                    Result.OWNER_CLEANUP_REQUIRED
                }
            }
        }
}
