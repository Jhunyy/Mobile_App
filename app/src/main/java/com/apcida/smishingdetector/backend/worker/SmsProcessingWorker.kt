package com.apcida.smishingdetector.backend.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.network.ProductionTiming
import com.apcida.smishingdetector.controller.SmsController
import com.apcida.smishingdetector.model.data.ProcessingState

/** Existing unique worker path. Production transmission is disabled until the security gate opens. */
class SmsProcessingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val ID = "message_id"
        private const val PREFIX = "sms_ai_message_"
        suspend fun recoverOrphaned(context: Context) {
            val ids = AppDatabase.getInstance(context).messageDao()
                .getStaleAiMessageIds(System.currentTimeMillis() - ProductionTiming.ORPHAN_STALE_MS)
            ids.forEach { SmsController(context).markAiUnavailable(it) }
        }
        fun enqueue(context: Context, messageId: Long) {
            if (!AiProtectionConsentStore(context).snapshot().enabled ||
                !RemoteTransportGate.mayTransmit(null, false)) return
            val request = OneTimeWorkRequestBuilder<SmsProcessingWorker>()
                .setInputData(Data.Builder().putLong(ID, messageId).build())
                .addTag(AiProtectionConsentStore.AUTO_REMOTE_TAG).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$PREFIX$messageId", ExistingWorkPolicy.KEEP, request)
        }
    }
    override suspend fun doWork(): Result {
        val id = inputData.getLong(ID, 0)
        if (id <= 0) return Result.failure()
        val message = AppDatabase.getInstance(applicationContext).messageDao().getMessageById(id)
            ?: return Result.success()
        if (message.verdictSource == "LEGACY_ON_DEVICE") return Result.success()
        val consent = AiProtectionConsentStore(applicationContext).snapshot()
        val controller = SmsController(applicationContext)
        if (!consent.enabled || consent.generation != message.consentGeneration ||
            message.processingState != ProcessingState.AI_QUEUED.name) {
            controller.markConsentDisabled(id)
            return Result.success()
        }
        // No production endpoint or per-install credential is provisioned. Never use the debug LAN client here.
        if (!RemoteTransportGate.mayTransmit(null, false)) {
            controller.markAiUnavailable(id)
            return Result.success()
        }
        controller.markAiUnavailable(id)
        return Result.failure()
    }
}
