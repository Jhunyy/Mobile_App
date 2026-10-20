package com.apcida.smishingdetector.backend.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.controller.SmsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistent on-device AI work; the shared Gemma validator serializes inference. */
class SmsProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SmsProcessingWorker"
        private const val INPUT_MESSAGE_ID = "message_id"
        private const val MESSAGE_TAG_PREFIX = "sms_ai_message_"
        private const val MAX_AI_ATTEMPTS = 3
        private const val ORPHAN_AGE_MILLIS = 2 * 60 * 1000L

        suspend fun recoverOrphaned(context: Context) = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val staleIds = AppDatabase.getInstance(appContext).messageDao()
                .getStaleAiMessageIds(System.currentTimeMillis() - ORPHAN_AGE_MILLIS)
            if (staleIds.isEmpty()) return@withContext

            val workManager = WorkManager.getInstance(appContext)
            staleIds.forEach { messageId ->
                val workName = "$MESSAGE_TAG_PREFIX$messageId"
                val active = workManager.getWorkInfosForUniqueWork(workName).get()
                    .any { !it.state.isFinished }
                if (!active) {
                    Log.w(TAG, "Restoring missing AI work for message $messageId.")
                    enqueue(appContext, messageId)
                }
            }
        }

        fun enqueue(context: Context, messageId: Long) {
            val input = Data.Builder().putLong(INPUT_MESSAGE_ID, messageId).build()
            val request = OneTimeWorkRequestBuilder<SmsProcessingWorker>()
                .setInputData(input)
                .addTag("$MESSAGE_TAG_PREFIX$messageId")
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$MESSAGE_TAG_PREFIX$messageId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong(INPUT_MESSAGE_ID, 0)
        if (messageId <= 0) return Result.failure()

        return try {
            val controller = SmsController(applicationContext)
            val aiCompleted = controller.analyzeWithAi(messageId)
            when {
                aiCompleted -> Result.success()
                runAttemptCount < MAX_AI_ATTEMPTS - 1 -> Result.retry()
                else -> {
                    Log.w(TAG, "AI analysis unavailable after the final attempt.")
                    Result.failure()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "SMS AI analysis attempt failed.", error)
            try {
                SmsController(applicationContext).markAiUnavailable(messageId)
            } catch (stateError: Exception) {
                Log.e(TAG, "Unable to persist the AI failure state.", stateError)
            }
            if (runAttemptCount < MAX_AI_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }
}
