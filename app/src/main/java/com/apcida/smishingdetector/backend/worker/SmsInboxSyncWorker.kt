package com.apcida.smishingdetector.backend.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apcida.smishingdetector.controller.SmsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Imports SMS that arrived before installation or were missed while the app was unavailable. */
class SmsInboxSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val WORK_NAME = "sms_inbox_sync"
        private const val TAG = "SmsInboxSyncWorker"
        private const val PREFS_NAME = "sms_inbox_sync"
        private const val LAST_SMS_ID = "last_sms_id"
        private const val BATCH_SIZE = 100

        fun enqueue(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED) return
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmsInboxSyncWorker>().build()
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED) return@withContext Result.success()

        val preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var lastSmsId = preferences.getLong(LAST_SMS_ID, 0L)
        var examined = 0
        var imported = 0
        try {
            val cursor = applicationContext.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms._ID} > ?", arrayOf(lastSmsId.toString()),
                "${Telephony.Sms._ID} ASC"
            ) ?: return@withContext Result.retry()

            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val controller = SmsController(applicationContext)
                while (examined < BATCH_SIZE && it.moveToNext()) {
                    coroutineContext.ensureActive()
                    val smsId = it.getLong(idColumn)
                    val body = it.getString(bodyColumn)?.trim().orEmpty()
                    val receivedAt = it.getLong(dateColumn)
                    if (body.isNotEmpty() && receivedAt > 0) {
                        val result = controller.receiveAndAnalyzeRules(
                            sender = it.getString(addressColumn) ?: "Unknown",
                            messageBody = body,
                            receivedAt = receivedAt,
                            sourceSmsId = smsId,
                            alertOnDetection = false
                        )
                        if (result.shouldEnqueueAi) {
                            SmsProcessingWorker.enqueue(applicationContext, result.messageId)
                            imported++
                        }
                    }
                    lastSmsId = smsId
                    preferences.edit().putLong(LAST_SMS_ID, lastSmsId).apply()
                    examined++
                }
            }
            Log.d(TAG, "Checked $examined stored SMS and imported $imported new messages.")
            if (examined == BATCH_SIZE) {
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<SmsInboxSyncWorker>().build()
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Stored SMS sync failed after checking $examined messages.", error)
            Result.retry()
        }
    }
}
