package com.apcida.smishingdetector.controller

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.detection.AiEligibilityPolicy
import com.apcida.smishingdetector.backend.detection.CombinedRiskScorer
import com.apcida.smishingdetector.backend.detection.ConversationRiskPolicy
import com.apcida.smishingdetector.backend.detection.DuplicatePolicy
import com.apcida.smishingdetector.backend.detection.FinalVerdictResolver
import com.apcida.smishingdetector.backend.detection.KeywordEngine
import com.apcida.smishingdetector.backend.detection.MessageSignals
import com.apcida.smishingdetector.backend.detection.RiskScorer
import com.apcida.smishingdetector.backend.detection.SenderSignals
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
import com.apcida.smishingdetector.util.ContactNameResolver
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

        val senderAddress = sender.takeIf { it.isNotBlank() && it != "Unknown" }
        val pendingMessage = Message(
            senderHash = senderHash,
            senderAddress = senderAddress,
            content = messageBody,
            contentHash = contentHash,
            receivedAt = receivedAt,
            processingState = ProcessingState.PENDING.name,
            aiAnalysisStatus = AiAnalysisStatus.NOT_STARTED.name
        )
        val messageId = messageRepository.insertMessage(pendingMessage)
        require(messageId > 0) { "Failed to persist incoming SMS." }

        val matchedKeywords = keywordEngine.analyze(messageBody)
        val isSavedContact = if (senderAddress != null &&
            ContactNameResolver.isEnabled(applicationContext)
        ) {
            try {
                ContactNameResolver.findName(applicationContext, senderAddress) != null
            } catch (error: Exception) {
                Log.w(TAG, "Contact lookup unavailable", error)
                false
            }
        } else {
            false
        }
        val riskScore = riskScorer.compute(matchedKeywords)
        val hasStrongRuleSignal = MessageSignals.hasStrongRuleSignal(messageBody, matchedKeywords)
        val ruleRiskLevel = thresholdEvaluator.evaluate(riskScore)
        val indicators = matchedKeywords.map { it.pattern }.distinct()
        val historyPoints = recentHistoryPoints(pendingMessage.copy(messageId = messageId))
        val combined = CombinedRiskScorer.score(
            ruleScore = riskScore,
            hasStrongRuleSignal = hasStrongRuleSignal,
            historyPoints = historyPoints,
            senderPoints = SenderSignals.points(senderAddress, messageBody)
        )
        val initialRiskLevel = CombinedRiskScorer.withSuspiciousFloor(ruleRiskLevel, combined.total)

        val ruleAnalyzed = pendingMessage.copy(
            messageId = messageId,
            riskScore = riskScore,
            combinedRiskScore = combined.total,
            ruleRiskPoints = combined.rulePoints,
            aiRiskPoints = combined.aiPoints,
            historyRiskPoints = combined.historyPoints,
            senderRiskPoints = combined.senderPoints,
            riskLevel = initialRiskLevel.name,
            deterministicRiskLevel = ruleRiskLevel.name,
            matchedIndicators = indicators.joinToString("\n"),
            isSavedContact = isSavedContact,
            hasStrongRuleSignal = hasStrongRuleSignal,
            isFlagged = initialRiskLevel != RiskLevel.SAFE,
            finalClassification = initialRiskLevel.name,
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
        refreshOwnHistoryWarning(messageId)

        if (thresholdEvaluator.isHighRisk(riskScore)) {
            showAlert(
                message = queuedMessage,
                riskLevel = ruleRiskLevel,
                matchedKeywords = matchedKeywords,
                rationale = "High-risk deterministic indicators were detected; AI analysis is pending."
            )
        } else if (historyPoints == 25 && ruleRiskLevel == RiskLevel.SAFE) {
            showAlert(
                message = queuedMessage,
                riskLevel = RiskLevel.SUSPICIOUS,
                matchedKeywords = matchedKeywords,
                rationale = "A recent message from this sender was confirmed as a scam."
            )
        }

        Log.d(TAG, "Persisted deterministic result and queued on-device AI analysis.")
        return IntakeResult(messageId, shouldEnqueueAi = true)
    }

    /** Runs one serialized Gemma attempt for a previously persisted message. */
    suspend fun analyzeWithAi(messageId: Long, finalAttempt: Boolean = true): Boolean {
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
        val history = messageRepository.getPreviousMessages(
            historySenderHash(message),
            message.receivedAt,
            message.messageId,
            ConversationRiskPolicy.CONTEXT_WINDOW_MILLIS,
            ConversationRiskPolicy.MAX_CONTEXT_MESSAGES
        ).asReversed().map { it.content }

        val gemmaResult = withTimeoutOrNull(Constants.GEMMA_TIMEOUT_MILLIS) {
            GemmaManager.getReadyValidator(applicationContext).validate(
                messageBody = message.content,
                matchedKeywords = matchedIndicators,
                conversationHistory = history
            )
        } ?: GemmaResult.fallback("Gemma inference timed out.")

        val finalVerdict = if (gemmaResult.isSuccessful || finalAttempt) {
            FinalVerdictResolver.resolve(
                ruleRiskLevel,
                gemmaResult,
                isSavedContact = message.isSavedContact,
                hasStrongRuleSignal = message.hasStrongRuleSignal
            )
        } else {
            FinalVerdictResolver.Verdict(ruleRiskLevel, ruleRiskLevel != RiskLevel.SAFE)
        }
        val processingState = when {
            gemmaResult.isSuccessful -> ProcessingState.COMPLETED
            finalAttempt -> ProcessingState.AI_FAILED
            else -> ProcessingState.AI_QUEUED
        }
        val aiStatus = when {
            gemmaResult.isSuccessful -> AiAnalysisStatus.AVAILABLE
            finalAttempt -> AiAnalysisStatus.UNAVAILABLE
            else -> AiAnalysisStatus.NOT_STARTED
        }
        val combined = CombinedRiskScorer.score(
            ruleScore = message.riskScore,
            hasStrongRuleSignal = message.hasStrongRuleSignal,
            historyPoints = recentHistoryPoints(message),
            senderPoints = message.senderRiskPoints,
            gemmaResult = gemmaResult
        )
        val finalRiskLevel = CombinedRiskScorer.withSuspiciousFloor(finalVerdict.riskLevel, combined.total)

        val completedMessage = analyzingMessage.copy(
            gemmaClassification = if (finalAttempt || gemmaResult.isSuccessful) gemmaResult.classification else null,
            gemmaConfidence = if (finalAttempt || gemmaResult.isSuccessful) gemmaResult.confidence else null,
            gemmaRationale = if (finalAttempt || gemmaResult.isSuccessful) gemmaResult.rationale else null,
            aiAnalysisStatus = aiStatus.name,
            processingState = processingState.name,
            combinedRiskScore = combined.total,
            ruleRiskPoints = combined.rulePoints,
            aiRiskPoints = combined.aiPoints,
            historyRiskPoints = combined.historyPoints,
            senderRiskPoints = combined.senderPoints,
            riskLevel = finalRiskLevel.name,
            finalClassification = finalRiskLevel.name,
            isFlagged = finalRiskLevel != RiskLevel.SAFE
        )
        messageRepository.updateMessage(completedMessage)
        refreshOwnHistoryWarning(messageId)

        if (gemmaResult.isSuccessful && gemmaResult.classification == Constants.GEMMA_SCAM) {
            refreshLaterWarnings(completedMessage)
        }

        if (gemmaResult.isSuccessful &&
            (finalRiskLevel == RiskLevel.SCAM || ruleRiskLevel == RiskLevel.SCAM)
        ) {
            showAlert(
                message = completedMessage,
                riskLevel = finalRiskLevel,
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
        val combined = CombinedRiskScorer.score(
            ruleScore = message.riskScore,
            hasStrongRuleSignal = message.hasStrongRuleSignal,
            historyPoints = recentHistoryPoints(message),
            senderPoints = message.senderRiskPoints
        )
        val finalRiskLevel = CombinedRiskScorer.withSuspiciousFloor(verdict.riskLevel, combined.total)

        messageRepository.updateMessage(
            message.copy(
                gemmaInvoked = true,
                gemmaClassification = fallback.classification,
                gemmaConfidence = fallback.confidence,
                gemmaRationale = fallback.rationale,
                aiAnalysisStatus = AiAnalysisStatus.UNAVAILABLE.name,
                processingState = ProcessingState.AI_FAILED.name,
                combinedRiskScore = combined.total,
                ruleRiskPoints = combined.rulePoints,
                aiRiskPoints = combined.aiPoints,
                historyRiskPoints = combined.historyPoints,
                senderRiskPoints = combined.senderPoints,
                riskLevel = finalRiskLevel.name,
                finalClassification = finalRiskLevel.name,
                isFlagged = finalRiskLevel != RiskLevel.SAFE
            )
        )
        refreshOwnHistoryWarning(messageId)
    }

    private fun historySenderHash(message: Message): String? =
        message.senderHash.takeIf { message.senderAddress != null }

    private suspend fun recentHistoryPoints(message: Message): Int {
        val previous = messageRepository.getPreviousRiskMessage(
            historySenderHash(message),
            message.receivedAt,
            message.messageId,
            ConversationRiskPolicy.WARNING_WINDOW_MILLIS
        )
        return ConversationRiskPolicy.historyPoints(listOfNotNull(previous), message.receivedAt)
    }

    private suspend fun refreshOwnHistoryWarning(messageId: Long) {
        val message = messageRepository.getMessageById(messageId) ?: return
        val historyPoints = recentHistoryPoints(message)
        if (historyPoints <= message.historyRiskPoints) return
        val gemmaResult = if (message.aiAnalysisStatus == AiAnalysisStatus.AVAILABLE.name &&
            message.gemmaClassification != null
        ) {
            GemmaResult(
                message.gemmaClassification,
                message.gemmaConfidence.orEmpty(),
                message.gemmaRationale.orEmpty()
            )
        } else null
        val combined = CombinedRiskScorer.score(
            ruleScore = message.riskScore,
            hasStrongRuleSignal = message.hasStrongRuleSignal,
            historyPoints = historyPoints,
            senderPoints = message.senderRiskPoints,
            gemmaResult = gemmaResult
        )
        messageRepository.applyHistoryWarning(messageId, combined.total, combined.historyPoints)
    }

    /** A preceding AI result can finish after a newer SMS has already been saved. */
    private suspend fun refreshLaterWarnings(source: Message) {
        if (historySenderHash(source) == null) return
        messageRepository.getLaterMessages(
            source.senderHash,
            source.receivedAt,
            source.messageId,
            ConversationRiskPolicy.WARNING_WINDOW_MILLIS
        ).forEach { refreshOwnHistoryWarning(it.messageId) }
    }

    /** Allows a failed analysis to be retried without changing its rule verdict. */
    suspend fun queueAiRetry(messageId: Long): Boolean {
        val message = messageRepository.getMessageById(messageId) ?: return false
        if (message.processingState != ProcessingState.AI_FAILED.name) return false

        messageRepository.updateMessage(
            message.copy(
                gemmaInvoked = false,
                gemmaClassification = null,
                gemmaConfidence = null,
                gemmaRationale = null,
                aiAnalysisStatus = AiAnalysisStatus.NOT_STARTED.name,
                processingState = ProcessingState.AI_QUEUED.name
            )
        )
        return true
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
