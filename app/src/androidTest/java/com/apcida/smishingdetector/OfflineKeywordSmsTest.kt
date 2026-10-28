package com.apcida.smishingdetector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.controller.SmsController
import com.apcida.smishingdetector.model.data.AiAnalysisStatus
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.model.entity.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the real offline intake path with synthetic rows; no inbox or network access. */
@RunWith(AndroidJUnit4::class)
class OfflineKeywordSmsTest {
    private suspend fun inspectSyntheticSms(body: String, verify: (Message) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).messageDao()
        val sender = "OfflineRules-${UUID.randomUUID()}"
        var insertedId = 0L
        try {
            val intake = SmsController(context).receiveAndAnalyzeRules(
                sender = sender,
                messageBody = body,
                receivedAt = System.currentTimeMillis(),
                alertOnDetection = false,
                historicalImport = false
            )
            insertedId = intake.messageId
            assertTrue(insertedId > 0)
            assertFalse(RemoteTransportGate.PRODUCTION_READY)
            assertFalse(intake.shouldEnqueueAi)
            val stored = dao.getMessageById(insertedId)
            assertNotNull(stored)
            val message = stored!!
            assertEquals("LOCAL_RULES", message.verdictSource)
            assertFalse(message.gemmaInvoked)
            assertNull(message.gemmaClassification)
            assertFalse(message.aiAnalysisStatus == AiAnalysisStatus.AVAILABLE.name)
            assertFalse(message.processingState == ProcessingState.AI_QUEUED.name)
            assertFalse(message.processingState == ProcessingState.AI_ANALYZING.name)
            verify(message)
        } finally {
            if (insertedId > 0) {
                dao.getMessageById(insertedId)?.let { row ->
                    AppDatabase.getInstance(context).messageKeywordDao()
                        .deleteMatchesForMessage(insertedId)
                    dao.deleteMessage(row)
                }
            }
        }
    }

    @Test fun duplicateBroadcastsStoreOneSyntheticMessage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).messageDao()
        val sender = "DuplicateRules-${UUID.randomUUID()}"
        val body = "Synthetic dinner reminder"
        val receivedAt = System.currentTimeMillis()
        var id = 0L
        try {
            val results = (1..8).map { async(Dispatchers.IO) {
                SmsController(context).receiveAndAnalyzeRules(sender, body, receivedAt,
                    alertOnDetection = false)
            } }.awaitAll()
            id = results.first().messageId
            assertTrue(id > 0)
            assertEquals(1, results.map { it.messageId }.distinct().size)
            assertTrue(results.none { it.shouldEnqueueAi })
            assertEquals("UNVERIFIED", dao.getMessageById(id)?.finalClassification)
        } finally {
            if (id > 0) dao.getMessageById(id)?.let { dao.deleteMessage(it) }
        }
    }

    @Test fun historicalSyntheticImportNeverQueuesRemoteAnalysis() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).messageDao()
        val sender = "HistoricalRules-${UUID.randomUUID()}"
        var id = 0L
        try {
            val result = SmsController(context).receiveAndAnalyzeRules(sender,
                "Synthetic old family reminder", System.currentTimeMillis() - 86_400_000L,
                alertOnDetection = false, historicalImport = true)
            id = result.messageId
            assertFalse(result.shouldEnqueueAi)
            assertEquals("UNVERIFIED", dao.getMessageById(id)?.finalClassification)
            assertFalse(dao.getMessageById(id)?.processingState == ProcessingState.AI_QUEUED.name)
        } finally {
            if (id > 0) dao.getMessageById(id)?.let { dao.deleteMessage(it) }
        }
    }

    @Test fun ordinarySmsStaysUnverifiedWithoutRemoteAnalysis() = runBlocking {
        inspectSyntheticSms("Hi, see you at the family dinner tonight at 7 PM.") { stored ->
            assertEquals(0f, stored.riskScore, 0.001f)
            assertEquals("SAFE", stored.deterministicRiskLevel)
            assertEquals("UNVERIFIED", stored.finalClassification)
            assertTrue(stored.matchedIndicators.isEmpty())
        }
    }

    @Test fun highRiskSmsKeepsLocalScamVerdictWithoutRemoteAnalysis() = runBlocking {
        inspectSyntheticSms("We are BDO security team. Reply with your OTP now to stop account suspension.") { stored ->
            assertTrue(stored.riskScore >= 60f)
            assertEquals("SCAM", stored.deterministicRiskLevel)
            assertEquals("SCAM", stored.finalClassification)
            assertTrue(stored.matchedIndicators.contains("CREDENTIAL_REQUEST"))
        }
    }
}
