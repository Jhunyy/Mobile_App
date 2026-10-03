package com.apcida.smishingdetector.controller

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.detection.KeywordEngine
import com.apcida.smishingdetector.backend.detection.DecisionEngine
import com.apcida.smishingdetector.backend.detection.RiskScorer
import com.apcida.smishingdetector.backend.detection.ThresholdEvaluator
import com.apcida.smishingdetector.backend.gemma.GemmaManager
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Keyword
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.MessageKeyword
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.HashUtil
import com.apcida.smishingdetector.util.NotificationHelper

class SmsController internal constructor(
    private val messageRepository: MessageRepository,
    private val analyzeKeywords: suspend (String) -> List<Keyword>,
    private val saveKeywordMatches: suspend (List<MessageKeyword>) -> Unit,
    private val validateWithGemma: suspend (String, List<String>) -> GemmaResult,
    private val notifyScam: (DetectionResult, Long) -> Unit
) {

    constructor(context: Context) : this(
        messageRepository = MessageRepository(AppDatabase.getInstance(context).messageDao()),
        analyzeKeywords = KeywordEngine(AppDatabase.getInstance(context).keywordDao())::analyze,
        saveKeywordMatches = AppDatabase.getInstance(context).messageKeywordDao()::insertAllMessageKeywords,
        validateWithGemma = GemmaManager.getValidator(context)::validate,
        notifyScam = { result, messageId ->
            NotificationHelper.showScamAlert(context.applicationContext, result, messageId)
        }
    )

    companion object {
        private const val TAG = "SmsController"
    }

    private val riskScorer = RiskScorer()
    private val thresholdEvaluator = ThresholdEvaluator()

    /**
     * Entry point called by SmsReceiver when a new SMS arrives.
     * Runs the full two-stage detection pipeline.
     */
    suspend fun onSmsReceived(sender: String, messageBody: String) {
        Log.d(TAG, "Processing new SMS")

        // Each received SMS is analyzed, even when its text repeats an earlier message.
        val contentHash = HashUtil.hashContent(messageBody)

        // ── Stage 1: Keyword Engine ───────────────────────────
        Log.d(TAG, "Stage 1: Running keyword engine...")
        val matchedKeywords = analyzeKeywords(messageBody)
        val riskScore = riskScorer.compute(matchedKeywords)
        val riskLevel = thresholdEvaluator.evaluate(riskScore)
        val isFlagged = riskLevel != RiskLevel.SAFE

        Log.d(TAG, "Stage 1 result — Score: $riskScore | Level: $riskLevel | Flagged: $isFlagged")

        // ── Save Initial Message Record ───────────────────────
        val message = Message(
            senderHash = HashUtil.hashSender(sender),
            content = messageBody,
            contentHash = contentHash,
            receivedAt = System.currentTimeMillis(),
            riskScore = riskScore,
            riskLevel = riskLevel.name,
            isFlagged = isFlagged,
            gemmaInvoked = false
        )

        val messageId = messageRepository.insertMessage(message)
        Log.d(TAG, "Message saved with ID: $messageId")

        // ── Save Keyword Matches ──────────────────────────────
        if (matchedKeywords.isNotEmpty()) {
            val messageKeywords = matchedKeywords.map { keyword ->
                MessageKeyword(
                    messageId = messageId,
                    keywordId = keyword.keywordId,
                    matchedText = keyword.pattern
                )
            }
            saveKeywordMatches(messageKeywords)
            Log.d(TAG, "Saved ${messageKeywords.size} keyword matches.")
        }

        // ── Stage 2: Gemma Contextual Validator ──────────────
        Log.d(TAG, "Stage 2: Invoking Gemma contextual validator...")

// Save immediately that Gemma has been invoked.
// This ensures the UI knows contextual analysis was attempted
// even if the native inference engine crashes or becomes unavailable.
        val gemmaAttemptedMessage = message.copy(
            messageId = messageId,
            gemmaInvoked = true
        )

        messageRepository.updateMessage(gemmaAttemptedMessage)

        Log.d(TAG, "Gemma invocation status saved.")

        val gemmaResult = validateWithGemma(
            messageBody,
            matchedKeywords.map { it.pattern }
        )

        Log.d(TAG, "Gemma result — Classification: ${gemmaResult.classification} | Confidence: ${gemmaResult.confidence}")

        // ── Update Message with Gemma Output ─────────────
        val finalRiskLevel = DecisionEngine.decide(riskLevel, gemmaResult, messageBody)
        val finalIsFlagged = finalRiskLevel != RiskLevel.SAFE

        val updatedMessage = message.copy(
            messageId = messageId,
            gemmaInvoked = true,
            gemmaClassification = gemmaResult.classification,
            gemmaConfidence = gemmaResult.confidence,
            gemmaRationale = gemmaResult.rationale,
            // Preserve keyword risk if Gemma is unavailable or uncertain.
            isFlagged = finalIsFlagged,
            riskLevel = finalRiskLevel.name
        )

        messageRepository.updateMessage(updatedMessage)
        Log.d(TAG, "Message updated with Gemma output.")

        // ── Build Final Detection Result ──────────────────
        val detectionResult = DetectionResult(
            messageContent = messageBody,
            riskScore = riskScore,
            riskLevel = finalRiskLevel,
            isFlagged = finalIsFlagged,
            matchedKeywords = matchedKeywords,
            gemmaInvoked = true,
            gemmaResult = gemmaResult,
            finalClassification = finalRiskLevel.name,
            finalRationale = when {
                finalRiskLevel == RiskLevel.SUSPICIOUS &&
                    gemmaResult.classification == Constants.GEMMA_LEGITIMATE ->
                    "Keyword indicators remain despite a benign contextual assessment. Verify independently."
                else -> gemmaResult.rationale
            }
        )

        // ── Notify UI if Scam ─────────────────────────────
        if (finalRiskLevel == RiskLevel.SCAM) {
            Log.d(TAG, "SCAM detected. Triggering alert notification.")
            notifyScam(detectionResult, messageId)
        } else {
            Log.d(TAG, "Final risk level: $finalRiskLevel. No scam alert shown.")
        }
    }
}
