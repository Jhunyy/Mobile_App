package com.apcida.smishingdetector.backend.worker

import android.content.Context
import com.apcida.smishingdetector.backend.remote.RemoteSmsCandidate
import java.util.UUID

/** Durable, one-batch authorization. Stores IDs only; SMS text never enters preferences or WorkManager. */
internal class HistoricalSmsBatchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("historical_sms_batch", Context.MODE_PRIVATE)

    data class Batch(val id: String, val generation: Long, val messageIds: List<Long>,
        val expiresAt: Long)

    companion object {
        private val lock = Any()
        private const val VALID_MS = 15 * 60 * 1000L
    }

    fun stored(): Batch? = synchronized(lock) {
        val id = prefs.getString("id", null) ?: return@synchronized null
        val generation = prefs.getLong("generation", 0)
        val expiresAt = prefs.getLong("expires_at", 0)
        val ids = prefs.getString("message_ids", null)?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty()
        if (generation <= 0 || expiresAt <= 0 || ids.isEmpty() ||
            ids.size > 3 || ids.distinct().size != ids.size) null
        else Batch(id, generation, ids, expiresAt)
    }

    fun active(): Batch? = stored()?.takeIf { it.expiresAt > System.currentTimeMillis() }

    fun start(messageIds: List<Long>, generation: Long): Batch? = synchronized(lock) {
        if (active() != null || generation <= 0 || messageIds.isEmpty() || messageIds.size > 3 ||
            messageIds.any { it <= 0 } || messageIds.distinct().size != messageIds.size) return@synchronized null
        val batch = Batch(UUID.randomUUID().toString(), generation, messageIds,
            System.currentTimeMillis() + VALID_MS)
        check(prefs.edit().putString("id", batch.id).putLong("generation", generation)
            .putString("message_ids", messageIds.joinToString(","))
            .putLong("expires_at", batch.expiresAt).commit())
        batch
    }

    fun allows(candidate: RemoteSmsCandidate): Boolean = synchronized(lock) {
        val batch = active()
        candidate.historicalImport && batch != null && candidate.manualBatchId == batch.id &&
            candidate.consentGeneration == batch.generation && candidate.messageId in batch.messageIds
    }

    fun clear(batchId: String): Batch? = synchronized(lock) {
        val batch = stored()?.takeIf { it.id == batchId } ?: return@synchronized null
        check(prefs.edit().clear().commit())
        batch
    }
}
