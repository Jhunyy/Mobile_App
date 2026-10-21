package com.apcida.smishingdetector

import android.app.NotificationManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.worker.SmsProcessingWorker
import com.apcida.smishingdetector.controller.SmsController
import com.apcida.smishingdetector.model.data.AiAnalysisStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineGemmaSmsTest {
    @Test
    fun smsIsAnalyzedWithWifiOff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = SmsController(context)
        val body = "Hi, see you at the family dinner tonight at 7 PM."
        val intake = controller.receiveAndAnalyzeRules("OfflineTest${System.currentTimeMillis()}", body)
        assertTrue(intake.shouldEnqueueAi)
        Log.i("OfflineGemmaSmsTest", "Queued message ${intake.messageId} with Wi-Fi off")
        SmsProcessingWorker.enqueue(context, intake.messageId)

        val database = AppDatabase.getInstance(context)
        try {
            val dao = database.messageDao()
            var status = ""
            repeat(120) {
                val message = dao.getMessageById(intake.messageId)!!
                status = message.aiAnalysisStatus
                if (status == AiAnalysisStatus.AVAILABLE.name) {
                    Log.i("OfflineGemmaSmsTest", "Message ${intake.messageId} AI available: ${message.gemmaClassification}")
                    return@runBlocking
                }
                if (status == AiAnalysisStatus.UNAVAILABLE.name) {
                    Log.e("OfflineGemmaSmsTest", "Message ${intake.messageId} AI unavailable: ${message.gemmaRationale}")
                    assertEquals(AiAnalysisStatus.AVAILABLE.name, status)
                }
                delay(1_000)
            }
            assertEquals(AiAnalysisStatus.AVAILABLE.name, status)
        } finally {
            // Keep synthetic SMS and any alerts out of the user's inbox.
            val notifications = context.getSystemService(NotificationManager::class.java)
            database.messageDao().getAllMessages().first()
                .filter { it.senderAddress?.startsWith("OfflineTest") == true }
                .forEach { message ->
                    database.messageKeywordDao().deleteMatchesForMessage(message.messageId)
                    database.messageDao().deleteMessage(message)
                    notifications.cancel(message.messageId.toInt())
                }
        }
    }
}
