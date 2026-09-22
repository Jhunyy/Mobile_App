package com.apcida.smishingdetector.backend.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.controller.SmsController

/** Persistent, process-independent and serialized on-device AI queue. */
class SmsProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SmsProcessingWorker"
        private const val UNIQUE_AI_QUEUE = "sms_ai_analysis_queue"
        private const val INPUT_MESSAGE_ID = "message_id"
        private const val MAX_AI_ATTEMPTS = 3

        fun enqueue(context: Context, messageId: Long) {
            val input = Data.Builder().putLong(INPUT_MESSAGE_ID, messageId).build()
            val request = OneTimeWorkRequestBuilder<SmsProcessingWorker>()
                .setInputData(input)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_AI_QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
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
