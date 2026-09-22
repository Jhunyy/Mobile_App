package com.apcida.smishingdetector.controller

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.detection.AiEligibilityPolicy
import com.apcida.smishingdetector.backend.detection.DuplicatePolicy
import com.apcida.smishingdetector.backend.detection.FinalVerdictResolver
import com.apcida.smishingdetector.backend.detection.KeywordEngine
import com.apcida.smishingdetector.backend.detection.RiskScorer
import com.apcida.smishingdetector.backend.detection.ThresholdEvaluator
import com.apcida.smishingdetector.backend.gemma.GemmaManager
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.model.data.AiAnalysisStatus
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Keyword
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.MessageKeyword
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.HashUtil
import com.apcida.smishingdetector.util.NotificationHelper
import kotlinx.coroutines.withTimeoutOrNull

class SmsController(context: Context) {

    data class IntakeResult(val messageId: Long, val shouldEnqueueAi: Boolean)

    companion object {
        private const val TAG = "SmsController"
    }

    private val applicationContext = context.applicationContext
    private val database = AppDatabase.getInstance(applicationContext)
    private val messageRepository = MessageRepository(database.messageDao())
    private val keywordEngine = KeywordEngine(database.keywordDao())
    private val riskScorer = RiskScorer()
    private val thresholdEvaluator = ThresholdEvaluator()

    /** Persists first, evaluates rules, and queues every non-duplicate SMS for AI. */
    suspend fun receiveAndAnalyzeRules(
        sender: String,
        messageBody: String,
        receivedAt: Long = System.currentTimeMillis()
    ): IntakeResult {
        DatabaseSeeder(applicationContext, database).seedIfFirstLaunch()

        val senderHash = HashUtil.hashSender(sender)
        val contentHash = HashUtil.hashContent(messageBody)
        val duplicate = messageRepository.findRecentDuplicate(
            senderHash,
            contentHash,
            DuplicatePolicy.windowStart(receivedAt),
            receivedAt
        )
        if (duplicate != null) {
            Log.d(TAG, "Suppressed a duplicate SMS broadcast within the time window.")
            return IntakeResult(duplicate.messageId, shouldEnqueueAi = false)
        }

        val pendingMessage = Message(
            senderHash = senderHash,
            content = messageBody,
            contentHash = contentHash,
            receivedAt = receivedAt,
            processingState = ProcessingState.PENDING.name,
            aiAnalysisStatus = AiAnalysisStatus.NOT_STARTED.name
        )
        val messageId = messageRepository.insertMessage(pendingMessage)
        require(messageId > 0) { "Failed to persist incoming SMS." }

        val matchedKeywords = keywordEngine.analyze(messageBody)
        val riskScore = riskScorer.compute(matchedKeywords)
        val ruleRiskLevel = thresholdEvaluator.evaluate(riskScore)
        val indicators = matchedKeywords.map { it.pattern }.distinct()

        val ruleAnalyzed = pendingMessage.copy(
            messageId = messageId,
            riskScore = riskScore,
            riskLevel = ruleRiskLevel.name,
            deterministicRiskLevel = ruleRiskLevel.name,
            matchedIndicators = indicators.joinToString("\n"),
            isFlagged = ruleRiskLevel != RiskLevel.SAFE,
            finalClassification = ruleRiskLevel.name,
            processingState = ProcessingState.RULE_ANALYZED.name
        )
        messageRepository.updateMessage(ruleAnalyzed)

        val persistedMatches = matchedKeywords
            .filter { it.keywordId > 0 }
            .map { keyword ->
                MessageKeyword(
                    messageId = messageId,
                    keywordId = keyword.keywordId,
                    matchedText = keyword.pattern
                )
            }
        if (persistedMatches.isNotEmpty()) {
            database.messageKeywordDao().insertAllMessageKeywords(persistedMatches)
        }

        check(AiEligibilityPolicy.shouldQueue(ruleRiskLevel))
        val queuedMessage = ruleAnalyzed.copy(processingState = ProcessingState.AI_QUEUED.name)
        messageRepository.updateMessage(queuedMessage)

        if (thresholdEvaluator.isHighRisk(riskScore)) {
            showAlert(
                message = queuedMessage,
                riskLevel = ruleRiskLevel,
                matchedKeywords = matchedKeywords,
                rationale = "High-risk deterministic indicators were detected; AI analysis is pending."
            )
        }

        Log.d(TAG, "Persisted deterministic result and queued on-device AI analysis.")
        return IntakeResult(messageId, shouldEnqueueAi = true)
    }

    /** Runs one serialized Gemma attempt for a previously persisted message. */
    suspend fun analyzeWithAi(messageId: Long): Boolean {
        val message = messageRepository.getMessageById(messageId) ?: return true
        if (message.processingState == ProcessingState.COMPLETED.name) return true

        val ruleRiskLevel = runCatching {
            RiskLevel.valueOf(message.deterministicRiskLevel)
        }.getOrDefault(RiskLevel.SUSPICIOUS)

        val analyzingMessage = message.copy(
            gemmaInvoked = true,
            aiAnalysisStatus = AiAnalysisStatus.ANALYZING.name,
            processingState = ProcessingState.AI_ANALYZING.name
        )
        messageRepository.updateMessage(analyzingMessage)

        val matchedIndicators = message.matchedIndicators
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        val gemmaResult = withTimeoutOrNull(Constants.GEMMA_TIMEOUT_MILLIS) {
            GemmaManager.getReadyValidator(applicationContext).validate(
                messageBody = message.content,
                matchedKeywords = matchedIndicators
            )
        } ?: GemmaResult.fallback("Gemma inference timed out.")

        val finalVerdict = FinalVerdictResolver.resolve(ruleRiskLevel, gemmaResult)
        val processingState = if (gemmaResult.isSuccessful) {
            ProcessingState.COMPLETED
        } else {
            ProcessingState.AI_FAILED
        }
        val aiStatus = if (gemmaResult.isSuccessful) {
            AiAnalysisStatus.AVAILABLE
        } else {
            AiAnalysisStatus.UNAVAILABLE
        }

        val completedMessage = analyzingMessage.copy(
            gemmaClassification = gemmaResult.classification,
            gemmaConfidence = gemmaResult.confidence,
            gemmaRationale = gemmaResult.rationale,
            aiAnalysisStatus = aiStatus.name,
            processingState = processingState.name,
            riskLevel = finalVerdict.riskLevel.name,
            finalClassification = finalVerdict.riskLevel.name,
            isFlagged = finalVerdict.isFlagged
        )
        messageRepository.updateMessage(completedMessage)

        if (gemmaResult.isSuccessful &&
            (finalVerdict.riskLevel == RiskLevel.SCAM || ruleRiskLevel == RiskLevel.SCAM)
        ) {
            showAlert(
                message = completedMessage,
                riskLevel = finalVerdict.riskLevel,
                matchedKeywords = emptyList(),
                rationale = gemmaResult.rationale
            )
        }

        Log.d(TAG, "Stored combined verdict with AI status ${aiStatus.name}.")
        return gemmaResult.isSuccessful
    }

    /** Records an unexpected worker-level failure without weakening the rule result. */
    suspend fun markAiUnavailable(messageId: Long) {
        val message = messageRepository.getMessageById(messageId) ?: return
        val ruleRiskLevel = runCatching {
            RiskLevel.valueOf(message.deterministicRiskLevel)
        }.getOrDefault(RiskLevel.SUSPICIOUS)
        val fallback = GemmaResult.fallback()
        val verdict = FinalVerdictResolver.resolve(ruleRiskLevel, fallback)

        messageRepository.updateMessage(
            message.copy(
                gemmaInvoked = true,
                gemmaClassification = fallback.classification,
                gemmaConfidence = fallback.confidence,
                gemmaRationale = fallback.rationale,
                aiAnalysisStatus = AiAnalysisStatus.UNAVAILABLE.name,
                processingState = ProcessingState.AI_FAILED.name,
                riskLevel = verdict.riskLevel.name,
                finalClassification = verdict.riskLevel.name,
                isFlagged = verdict.isFlagged
            )
        )
    }

    private fun showAlert(
        message: Message,
        riskLevel: RiskLevel,
        matchedKeywords: List<Keyword>,
        rationale: String
    ) {
        NotificationHelper.showScamAlert(
            applicationContext,
            DetectionResult(
                messageContent = message.content,
                riskScore = message.riskScore,
                riskLevel = riskLevel,
                isFlagged = true,
                matchedKeywords = matchedKeywords,
                gemmaInvoked = message.gemmaInvoked,
                finalClassification = riskLevel.name,
                finalRationale = rationale
            ),
            message.messageId
        )
    }
}
