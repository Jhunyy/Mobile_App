package com.apcida.smishingdetector.controller

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.detection.KeywordEngine
import com.apcida.smishingdetector.backend.detection.RiskScorer
import com.apcida.smishingdetector.backend.detection.ThresholdEvaluator
import com.apcida.smishingdetector.backend.gemma.GemmaValidator
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.MessageKeyword
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.HashUtil

class SmsController(private val context: Context) {

    companion object {
        private const val TAG = "SmsController"
    }

    private val database = AppDatabase.getInstance(context)
    private val messageRepository = MessageRepository(database.messageDao())
    private val keywordEngine = KeywordEngine(database.keywordDao())
    private val riskScorer = RiskScorer()
    private val thresholdEvaluator = ThresholdEvaluator()
    private val gemmaValidator = GemmaValidator(context)

    /**
     * Entry point called by SmsReceiver when a new SMS arrives.
     * Runs the full two-stage detection pipeline.
     */
    suspend fun onSmsReceived(sender: String, messageBody: String) {
        Log.d(TAG, "Processing new SMS from: $sender")

        // ── Duplicate Check ───────────────────────────────────
        val contentHash = HashUtil.hashContent(messageBody)
        val existing = messageRepository.getMessageByHash(contentHash)
        if (existing != null) {
            Log.d(TAG, "Duplicate message detected. Skipping.")
            return
        }

        // ── Stage 1: Keyword Engine ───────────────────────────
        Log.d(TAG, "Stage 1: Running keyword engine...")
        val matchedKeywords = keywordEngine.analyze(messageBody)
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
            database.messageKeywordDao().insertAllMessageKeywords(messageKeywords)
            Log.d(TAG, "Saved ${messageKeywords.size} keyword matches.")
        }

        // ── Stage 2: Gemma Contextual Validator ──────────────
        if (thresholdEvaluator.shouldInvokeGemma(riskScore)) {
            Log.d(TAG, "Stage 2: Invoking Gemma contextual validator...")

            val gemmaResult = gemmaValidator.validate(
                messageBody = messageBody,
                matchedKeywords = matchedKeywords.map { it.pattern }
            )

            Log.d(TAG, "Gemma result — Classification: ${gemmaResult.classification} | Confidence: ${gemmaResult.confidence}")

            // ── Update Message with Gemma Output ─────────────
            val finalClassification = gemmaResult.classification
            val isScam = finalClassification == Constants.GEMMA_SCAM

            val updatedMessage = message.copy(
                messageId = messageId,
                gemmaInvoked = true,
                gemmaClassification = gemmaResult.classification,
                gemmaConfidence = gemmaResult.confidence,
                gemmaRationale = gemmaResult.rationale,
                // If Gemma says LEGITIMATE, override flag to false
                isFlagged = isScam,
                riskLevel = if (isScam) RiskLevel.SCAM.name else RiskLevel.SAFE.name
            )

            messageRepository.updateMessage(updatedMessage)
            Log.d(TAG, "Message updated with Gemma output.")

            // ── Build Final Detection Result ──────────────────
            val detectionResult = DetectionResult(
                messageContent = messageBody,
                riskScore = riskScore,
                riskLevel = riskLevel,
                isFlagged = isScam,
                matchedKeywords = matchedKeywords,
                gemmaInvoked = true,
                gemmaResult = gemmaResult,
                finalClassification = finalClassification,
                finalRationale = gemmaResult.rationale
            )

            // ── Notify UI if Scam ─────────────────────────────
            if (isScam) {
                Log.d(TAG, "SCAM detected. Triggering alert notification.")
                triggerScamAlert(context, detectionResult, messageId)
            } else {
                Log.d(TAG, "Gemma reclassified as LEGITIMATE. No alert shown.")
            }

        } else {
            // Safe path — Gemma not invoked
            Log.d(TAG, "Message is SAFE. No Gemma invocation needed.")
        }
    }

    /**
     * Triggers a system notification alerting the user of a detected scam.
     * The notification is handled by the NotificationHelper (to be implemented).
     */
    private fun triggerScamAlert(
        context: Context,
        result: DetectionResult,
        messageId: Long
    ) {
        // TODO: Implement NotificationHelper.showScamAlert(context, result, messageId)
        // This will be connected when we build the notification system
        Log.d(TAG, "Alert triggered for message ID: $messageId")
        Log.d(TAG, "Rationale: ${result.finalRationale}")
    }
}