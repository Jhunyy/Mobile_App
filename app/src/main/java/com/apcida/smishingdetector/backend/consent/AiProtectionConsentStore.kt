package com.apcida.smishingdetector.backend.consent

import android.content.Context
import androidx.work.WorkManager
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.worker.ProductionRevocationWorker

/** Remote analysis is opt-in. Android SMS permission and research participation are independent. */
class AiProtectionConsentStore(
    context: Context,
    preferenceName: String = "ai_protection_consent",
    private val hasProductionCredential: () -> Boolean =
        { ProductionCredentialStore(context).hasStoredCredential() },
    private val enqueueDeletion: (Context) -> Unit =
        { ProductionRevocationWorker.enqueueIfPending(it) }
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    data class Snapshot(val enabled: Boolean, val grantedAt: Long, val generation: Long)

    enum class RemoteDeletionStatus {
        NOT_REQUIRED, PENDING, CONFIGURATION_REQUIRED, RETRY_PAUSED,
        CREDENTIAL_UNAVAILABLE, AUTH_REJECTED, CONFIRMED
    }

    fun remoteDeletionPending(): Boolean = prefs.getBoolean("remote_deletion_pending", false)

    fun remoteDeletionStatus(): RemoteDeletionStatus =
        runCatching { RemoteDeletionStatus.valueOf(
            prefs.getString("remote_deletion_status", RemoteDeletionStatus.NOT_REQUIRED.name) ?: "") }
            .getOrDefault(RemoteDeletionStatus.NOT_REQUIRED)

    @Synchronized fun markRemoteDeletionStatus(status: RemoteDeletionStatus) {
        if (remoteDeletionPending()) {
            check(prefs.edit().putString("remote_deletion_status", status.name).commit())
        }
    }

    @Synchronized fun markRemoteDeletionConfirmed() {
        check(prefs.edit().putBoolean("remote_deletion_pending", false)
            .putString("remote_deletion_status", RemoteDeletionStatus.CONFIRMED.name).commit())
    }

    fun snapshot(): Snapshot = Snapshot(
        prefs.getBoolean("enabled", false) && prefs.getInt("disclosure_version", 0) == DISCLOSURE_VERSION,
        prefs.getLong("granted_at", 0), prefs.getLong("generation", 0)
    )

    fun hasSeenDisclosure(): Boolean = prefs.getBoolean("disclosure_seen", false)

    fun markDisclosureSeen() { prefs.edit().putBoolean("disclosure_seen", true).commit() }

    @Synchronized fun grant(now: Long = System.currentTimeMillis()): Snapshot {
        val generation = prefs.getLong("generation", 0) + 1
        check(prefs.edit().putBoolean("enabled", true).putBoolean("disclosure_seen", true)
            .putInt("disclosure_version", DISCLOSURE_VERSION).putLong("granted_at", now)
            .putLong("generation", generation).commit())
        return snapshot()
    }

    @Synchronized fun revoke() {
        val generation = prefs.getLong("generation", 0) + 1
        val pending = remoteDeletionPending() || runCatching { hasProductionCredential() }.getOrDefault(true)
        // Local opt-out and the deletion marker share one durable commit.
        check(prefs.edit().putBoolean("enabled", false).putLong("granted_at", 0)
            .putLong("generation", generation).putBoolean("remote_deletion_pending", pending)
            .putString("remote_deletion_status", if (pending) RemoteDeletionStatus.PENDING.name
                else RemoteDeletionStatus.NOT_REQUIRED.name).commit())
        runCatching { WorkManager.getInstance(app).cancelAllWorkByTag(AUTO_REMOTE_TAG) }
        if (pending) {
            runCatching { enqueueDeletion(app) }
                .onFailure { markRemoteDeletionStatus(RemoteDeletionStatus.RETRY_PAUSED) }
        }
    }

    suspend fun reconcileRevokedPending() {
        val current = snapshot()
        if (!current.enabled) {
            AppDatabase.getInstance(app).messageDao().markPendingRemoteConsentDisabled(current.generation)
        }
    }

    companion object {
        const val DISCLOSURE_VERSION = 1
        const val AUTO_REMOTE_TAG = "automatic_remote_sms"
    }
}
