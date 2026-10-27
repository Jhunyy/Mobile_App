package com.apcida.smishingdetector.backend.consent

import android.content.Context
import androidx.work.WorkManager
import com.apcida.smishingdetector.backend.database.AppDatabase

/** Remote analysis is opt-in. Android SMS permission and research participation are independent. */
class AiProtectionConsentStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("ai_protection_consent", Context.MODE_PRIVATE)

    data class Snapshot(val enabled: Boolean, val grantedAt: Long, val generation: Long)

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
        check(prefs.edit().putBoolean("enabled", false).putLong("granted_at", 0)
            .putLong("generation", generation).commit())
        WorkManager.getInstance(app).cancelAllWorkByTag(AUTO_REMOTE_TAG)
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
