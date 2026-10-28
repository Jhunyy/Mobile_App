package com.apcida.smishingdetector.backend.worker

import android.content.Context
import com.apcida.smishingdetector.BuildConfig
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.backend.network.ReportUploadManager
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Persists report upload attempts when the user leaves the report screen. */
class ReportUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ReportUploadWorker"
        private const val UNIQUE_REPORT_UPLOAD = "report_upload_queue"
        private const val MAX_ATTEMPTS = 6

        fun enqueue(context: Context) {
            // Local reports remain saved; this build has no approved outbound report credential.
            if (BuildConfig.REPORT_API_KEY.isBlank()) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_REPORT_UPLOAD,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            if (ReportUploadManager(applicationContext).uploadPendingReports()) {
                Result.success()
            } else if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Log.w(TAG, "Reports remain pending after the final upload attempt.")
                Result.failure()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Report upload attempt failed.")
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }
}
