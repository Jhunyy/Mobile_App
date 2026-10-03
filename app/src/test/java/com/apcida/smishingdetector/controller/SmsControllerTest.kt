package com.apcida.smishingdetector.controller

import com.apcida.smishingdetector.backend.database.dao.MessageDao
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Keyword
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.MessageKeyword
import com.apcida.smishingdetector.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmsControllerTest {
    @Test
    fun everyScoreRunsKeywordsThenGemmaAndSavesItsResult() = runBlocking {
        for (score in listOf(0f, 29f, 30f, 59f, 60f, 61f)) {
            val fixture = Fixture(score, legitimate())
            fixture.controller.onSmsReceived("sender", "Sample SMS")

            assertEquals(listOf("keywords", "gemma"), fixture.stages)
            assertEquals(listOf("Sample SMS" to fixture.keywords.map { it.pattern }), fixture.gemmaInputs)
            val saved = fixture.dao.messages.single()
            assertEquals(score, saved.riskScore, 0.001f)
            assertTrue(saved.gemmaInvoked)
            assertEquals(Constants.GEMMA_LEGITIMATE, saved.gemmaClassification)
            assertEquals("Contextual result", saved.gemmaRationale)
            assertEquals(Constants.CONFIDENCE_HIGH, saved.gemmaConfidence)
            val expected = if (score >= 60f) RiskLevel.SUSPICIOUS else RiskLevel.SAFE
            assertEquals(expected.name, saved.riskLevel)
            assertEquals(expected != RiskLevel.SAFE, saved.isFlagged)
            assertEquals(fixture.keywords.size, fixture.matches.size)
            assertTrue(fixture.matches.all { it.messageId == saved.messageId })
            assertTrue(fixture.alerts.isEmpty())
        }
    }

    @Test
    fun gemmaCanDetectScamWithoutAnyKeywordMatches() = runBlocking {
        val fixture = Fixture(0f, legitimate().copy(classification = Constants.GEMMA_SCAM))
        fixture.controller.onSmsReceived("sender", "Please send cash now")

        val saved = fixture.dao.messages.single()
        assertEquals(RiskLevel.SCAM.name, saved.riskLevel)
        assertTrue(saved.isFlagged)
        val (alert, messageId) = fixture.alerts.single()
        assertEquals(saved.messageId, messageId)
        assertEquals(Constants.GEMMA_SCAM, alert.finalClassification)
        assertTrue(alert.gemmaInvoked)
    }

    @Test
    fun lowConfidenceScamVerdictDoesNotFlagOrdinaryReminder() = runBlocking {
        val fixture = Fixture(0f, legitimate().copy(
            classification = Constants.GEMMA_SCAM,
            confidence = Constants.CONFIDENCE_LOW,
            rationale = "The reminder does not name the meeting attendees."
        ))
        fixture.controller.onSmsReceived("sender", "Hi! Just reminding you that our meeting is tomorrow at 10 AM. See you then.")

        val saved = fixture.dao.messages.single()
        assertEquals(RiskLevel.SAFE.name, saved.riskLevel)
        assertFalse(saved.isFlagged)
        assertTrue(fixture.alerts.isEmpty())
    }

    @Test
    fun uncertainGemmaDoesNotMarkMessageSafe() = runBlocking {
        val fixture = Fixture(35f, legitimate().copy(classification = Constants.GEMMA_UNCERTAIN))
        fixture.controller.onSmsReceived("sender", "Ambiguous SMS")

        val saved = fixture.dao.messages.single()
        assertEquals(RiskLevel.SUSPICIOUS.name, saved.riskLevel)
        assertTrue(saved.isFlagged)
        assertTrue(fixture.alerts.isEmpty())
    }

    @Test
    fun repeatedContentIsAnalyzedOnEachReceipt() = runBlocking {
        val fixture = Fixture(0f, legitimate())
        repeat(2) { fixture.controller.onSmsReceived("sender", "Same SMS") }

        assertEquals(listOf("keywords", "gemma", "keywords", "gemma"), fixture.stages)
        assertEquals(2, fixture.gemmaInputs.size)
        assertEquals(2, fixture.dao.messages.size)
        assertTrue(fixture.dao.messages.all { it.gemmaInvoked })
        assertEquals(2, fixture.dao.messages.map { it.messageId }.distinct().size)
    }

    @Test
    fun unavailableGemmaPreservesKeywordRiskAndRecordsFallback() = runBlocking {
        for ((score, expectedRisk) in listOf(0f to RiskLevel.SAFE, 30f to RiskLevel.SUSPICIOUS, 60f to RiskLevel.SCAM)) {
            val fixture = Fixture(score, GemmaResult.fallback())
            fixture.controller.onSmsReceived("sender", "Sample SMS")

            val saved = fixture.dao.messages.single()
            assertTrue(saved.gemmaInvoked)
            assertEquals(Constants.GEMMA_UNCERTAIN, saved.gemmaClassification)
            assertEquals(expectedRisk.name, saved.riskLevel)
            assertEquals(expectedRisk != RiskLevel.SAFE, saved.isFlagged)
            assertEquals(if (expectedRisk == RiskLevel.SCAM) 1 else 0, fixture.alerts.size)
        }
    }

    private fun legitimate() = GemmaResult(
        classification = Constants.GEMMA_LEGITIMATE,
        confidence = Constants.CONFIDENCE_HIGH,
        rationale = "Contextual result"
    )

    private class Fixture(score: Float, result: GemmaResult) {
        val dao = InMemoryMessageDao()
        val stages = mutableListOf<String>()
        val gemmaInputs = mutableListOf<Pair<String, List<String>>>()
        val matches = mutableListOf<MessageKeyword>()
        val alerts = mutableListOf<Pair<DetectionResult, Long>>()
        val keywords = if (score == 0f) emptyList() else listOf(
            Keyword(keywordId = 1, pattern = "indicator", patternType = "URL_PATTERN", weight = score)
        )
        val controller = SmsController(
            messageRepository = MessageRepository(dao),
            analyzeKeywords = { stages.add("keywords"); keywords },
            saveKeywordMatches = { matches.addAll(it) },
            validateWithGemma = { body, patterns ->
                stages.add("gemma")
                gemmaInputs.add(body to patterns)
                result
            },
            notifyScam = { detection, id -> alerts.add(detection to id) }
        )
    }

    private class InMemoryMessageDao : MessageDao {
        val messages = mutableListOf<Message>()
        override suspend fun insertMessage(message: Message): Long {
            val id = (messages.size + 1).toLong()
            messages.add(message.copy(messageId = id))
            return id
        }
        override suspend fun updateMessage(message: Message) {
            messages[messages.indexOfFirst { it.messageId == message.messageId }] = message
        }
        override suspend fun getMessageByHash(hash: String) = messages.find { it.contentHash == hash }
        override suspend fun getMessageById(messageId: Long) = messages.find { it.messageId == messageId }
        override fun getAllMessages(): Flow<List<Message>> = flowOf(messages.toList())
        override fun getFlaggedMessages(): Flow<List<Message>> = flowOf(messages.filter { it.isFlagged })
        override suspend fun deleteMessage(message: Message) { messages.remove(message) }
        override suspend fun deleteAllMessages() { messages.clear() }
        override suspend fun getMessageCount() = messages.size
        override suspend fun getScamMessageCount() = messages.count { it.isFlagged }
    }
}
