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
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.remote.AutomaticRemoteCoordinator
import com.apcida.smishingdetector.backend.remote.ProductionRemoteRuntime
import com.apcida.smishingdetector.backend.remote.ProductionSmsTransport
import com.apcida.smishingdetector.backend.remote.RemoteSmsCandidate
import com.apcida.smishingdetector.backend.remote.RoomRemoteAnalysisStateStore
import com.apcida.smishingdetector.model.data.ProcessingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Sends only the at-most-three older messages selected and confirmed by the user. */
class HistoricalSmsBatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "manual_historical_sms_batch"
        private const val BATCH_ID = "batch_id"
        const val DONE = "done"
        const val TOTAL = "total"
        const val VERIFIED = "verified"

        fun enqueue(context: Context, batchId: String) {
            val request = OneTimeWorkRequestBuilder<HistoricalSmsBatchWorker>()
                .setInputData(Data.Builder().putString(BATCH_ID, batchId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(AiProtectionConsentStore.AUTO_REMOTE_TAG).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(BATCH_ID) ?: return Result.failure()
        val app = applicationContext
        val store = HistoricalSmsBatchStore(app)
        val batch = store.stored()?.takeIf { it.id == batchId } ?: return Result.success()
        val consentStore = AiProtectionConsentStore(app)
        val dao = AppDatabase.getInstance(app).messageDao()
        var done = 0
        var verified = 0
        try {
            for (id in batch.messageIds) {
                if (isStopped || store.active()?.id != batchId) break
                val consent = consentStore.snapshot()
                if (!consent.enabled || consent.generation != batch.generation ||
                    !RemoteTransportGate.PRODUCTION_READY) break
                val requirements = ProductionRemoteRuntime.requirements(app, consent)
                if (!RemoteTransportGate.canTransmit(requirements)) break
                val message = dao.getMessageById(id) ?: continue
                if (message.receivedAt >= consent.grantedAt) continue
                val queued = message.processingState == ProcessingState.AI_QUEUED.name &&
                    message.consentGeneration == batch.generation
                if (!queued && dao.queueHistoricalAnalysis(id, batch.generation, consent.grantedAt) != 1) continue
                val current = dao.getMessageById(id) ?: continue
                val requestId = current.remoteRequestId ?: continue
                val origin = requirements.endpoint ?: break
                val host = requirements.approvedHost ?: break
                val candidate = RemoteSmsCandidate(id, current.senderAddress ?: "Unknown", current.content,
                    current.receivedAt, requestId, batch.generation, historicalImport = true,
                    manualBatchId = batchId)
                if (!store.allows(candidate)) break
                val authorization: (RemoteSmsCandidate) -> Boolean = { store.allows(it) }
                val transport = ProductionSmsTransport(origin, host, ProductionCredentialStore(app),
                    CloudflareAccessCredentialStore(app),
                    { ProductionRemoteRuntime.requirements(app, consentStore.snapshot()) },
                    manualHistoricalAuthorization = authorization)
                val outcome = AutomaticRemoteCoordinator({ consentStore.snapshot() },
                    RoomRemoteAnalysisStateStore(app), transport,
                    manualHistoricalAuthorization = authorization).analyze(candidate, requirements)
                done++
                if (outcome == AutomaticRemoteCoordinator.Outcome.VERIFIED) verified++
                setProgress(Data.Builder().putInt(DONE, done).putInt(TOTAL, batch.messageIds.size)
                    .putInt(VERIFIED, verified).build())
                if (outcome == AutomaticRemoteCoordinator.Outcome.CREDENTIAL_REVOKED ||
                    outcome == AutomaticRemoteCoordinator.Outcome.CONSENT_REVOKED ||
                    outcome == AutomaticRemoteCoordinator.Outcome.GATE_CLOSED ||
                    outcome == AutomaticRemoteCoordinator.Outcome.UNAVAILABLE) break
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                store.clear(batchId)
                for (id in batch.messageIds) {
                    val current = consentStore.snapshot()
                    if (current.enabled && current.generation == batch.generation)
                        dao.failPendingRemoteAnalysis(id, batch.generation)
                    else dao.disablePendingRemoteAnalysis(id, batch.generation)
                }
            }
        }
        return Result.success(Data.Builder().putInt(DONE, done).putInt(TOTAL, batch.messageIds.size)
            .putInt(VERIFIED, verified).build())
    }
}
