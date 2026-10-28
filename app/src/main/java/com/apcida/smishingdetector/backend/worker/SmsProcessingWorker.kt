package com.apcida.smishingdetector.backend.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionTiming
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.remote.AutomaticRemoteCoordinator
import com.apcida.smishingdetector.backend.remote.ProductionRemoteRuntime
import com.apcida.smishingdetector.backend.remote.ProductionSmsTransport
import com.apcida.smishingdetector.backend.remote.RemoteSmsCandidate
import com.apcida.smishingdetector.backend.remote.RoomRemoteAnalysisStateStore
import com.apcida.smishingdetector.controller.SmsController
import com.apcida.smishingdetector.model.data.ProcessingState
import java.util.concurrent.TimeUnit

/** Production route only. Consent and the closed gate are checked before every send. */
class SmsProcessingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        internal const val ID = "message_id"
        internal const val PREFIX = "sms_ai_message_"
        internal const val EXPIRY_PREFIX = "sms_ai_expiry_"

        /** Recover an intake interrupted between Room insert and WorkManager enqueue. */
        suspend fun recoverOrphaned(context: Context) {
            val app = context.applicationContext
            val dao = AppDatabase.getInstance(app).messageDao()
            val controller = SmsController(app)
            val stale = dao.getStaleAiMessageIds(System.currentTimeMillis() - ProductionTiming.ORPHAN_STALE_MS)
            stale.forEach { controller.markAiUnavailable(it) }
            val consent = AiProtectionConsentStore(app).snapshot()
            if (!consent.enabled) return
            dao.getQueuedRemoteMessageIds(consent.generation, consent.grantedAt)
                .forEach { enqueue(app, it) }
        }

        fun enqueue(context: Context, messageId: Long) {
            if (messageId <= 0) return
            val app = context.applicationContext
            val consent = AiProtectionConsentStore(app).snapshot()
            val ready = ProductionRemoteRuntime.canSchedule(app, consent)
            val request = OneTimeWorkRequestBuilder<SmsProcessingWorker>()
                .setInputData(Data.Builder().putLong(ID, messageId).build())
                .addTag(AiProtectionConsentStore.AUTO_REMOTE_TAG)
                .apply {
                    if (ready) setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                }.build()
            val expiry = OneTimeWorkRequestBuilder<SmsRemoteExpiryWorker>()
                .setInputData(Data.Builder().putLong(ID, messageId).build())
                .setInitialDelay(ProductionTiming.ORPHAN_STALE_MS, TimeUnit.MILLISECONDS)
                .addTag(AiProtectionConsentStore.AUTO_REMOTE_TAG).build()
            val work = WorkManager.getInstance(app)
            work.enqueueUniqueWork("$PREFIX$messageId", ExistingWorkPolicy.KEEP, request)
            work.enqueueUniqueWork("$EXPIRY_PREFIX$messageId", ExistingWorkPolicy.KEEP, expiry)
        }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ID, 0)
        if (id <= 0) return Result.failure()
        val app = applicationContext
        val dao = AppDatabase.getInstance(app).messageDao()
        val message = dao.getMessageById(id) ?: return Result.success()
        val controller = SmsController(app)
        val consentStore = AiProtectionConsentStore(app)
        val consent = consentStore.snapshot()
        when (AutomaticSmsWorkPolicy.decide(message, consent, System.currentTimeMillis())) {
            AutomaticSmsWorkPolicy.Decision.CONSENT_DISABLED -> {
                controller.markConsentDisabled(id)
                return Result.success()
            }
            AutomaticSmsWorkPolicy.Decision.STALE -> {
                controller.markAiUnavailable(id)
                return Result.success()
            }
            AutomaticSmsWorkPolicy.Decision.ALREADY_HANDLED -> return Result.success()
            AutomaticSmsWorkPolicy.Decision.READY -> Unit
        }
        if (!RemoteTransportGate.PRODUCTION_READY) {
            controller.markAiUnavailable(id)
            return Result.success()
        }
        val requirements = ProductionRemoteRuntime.requirements(app, consent)
        if (!RemoteTransportGate.canTransmit(requirements)) {
            controller.markAiUnavailable(id)
            return Result.success()
        }
        val requestId = message.remoteRequestId ?: run {
            controller.markAiUnavailable(id)
            return Result.success()
        }
        val origin = requirements.endpoint ?: run {
            controller.markAiUnavailable(id)
            return Result.success()
        }
        val host = requirements.approvedHost ?: run {
            controller.markAiUnavailable(id)
            return Result.success()
        }
        val credentials = ProductionCredentialStore(app)
        val transport = ProductionSmsTransport(origin, host, credentials,
            { ProductionRemoteRuntime.requirements(app, consentStore.snapshot()) })
        val candidate = RemoteSmsCandidate(id, message.senderAddress ?: "Unknown", message.content,
            message.receivedAt, requestId, message.consentGeneration, historicalImport = false)
        AutomaticRemoteCoordinator({ consentStore.snapshot() }, RoomRemoteAnalysisStateStore(app),
            transport).analyze(candidate, requirements)
        // One attempt. Backend idempotency handles explicit duplicates; no automatic SMS retry.
        return Result.success()
    }
}

/** Removes an offline or stalled queued request without sending SMS. */
class SmsRemoteExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(SmsProcessingWorker.ID, 0)
        if (id <= 0) return Result.failure()
        val message = AppDatabase.getInstance(applicationContext).messageDao().getMessageById(id)
            ?: return Result.success()
        if (message.verdictSource == "LOCAL_RULES" &&
            message.processingState in setOf(ProcessingState.AI_QUEUED.name,
                ProcessingState.AI_ANALYZING.name)) {
            if (SmsController(applicationContext).markAiUnavailable(id)) {
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork("${SmsProcessingWorker.PREFIX}$id")
            }
        }
        return Result.success()
    }
}
