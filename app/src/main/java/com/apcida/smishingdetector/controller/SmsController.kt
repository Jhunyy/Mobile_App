package com.apcida.smishingdetector.controller

import android.content.Context
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.consent.AutomaticAnalysisPolicy
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.detection.BackendKeywordRules
import com.apcida.smishingdetector.backend.detection.DuplicatePolicy
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.network.RemoteVerdictPolicy
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.model.data.AiAnalysisStatus
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.DetectionState
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.data.VerdictSource
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.HashUtil
import com.apcida.smishingdetector.util.NotificationHelper
import java.util.UUID

/** Incoming SMS: persist and warn locally first. Automatic transport remains security-gated. */
class SmsController(context: Context) {
    data class IntakeResult(val messageId: Long, val shouldEnqueueAi: Boolean)
    private val app = context.applicationContext
    private val repository = MessageRepository(AppDatabase.getInstance(app).messageDao())
    private val consent = AiProtectionConsentStore(app)

    suspend fun receiveAndAnalyzeRules(
        sender: String,
        messageBody: String,
        receivedAt: Long = System.currentTimeMillis(),
        sourceSmsId: Long? = null,
        alertOnDetection: Boolean = true,
        historicalImport: Boolean = false
    ): IntakeResult {
        require(messageBody.isNotBlank())
        val senderHash = HashUtil.hashSender(sender)
        val contentHash = HashUtil.hashContent(messageBody)
        val duplicate = if (sourceSmsId != null) {
            repository.getMessageBySourceSmsId(sourceSmsId)
                ?: repository.findMatchingStoredSms(senderHash, contentHash, receivedAt)
        } else repository.findRecentDuplicate(senderHash, contentHash,
            DuplicatePolicy.windowStart(receivedAt), receivedAt)
        if (duplicate != null) {
            if (sourceSmsId != null && duplicate.sourceSmsId == null) repository.bindSourceSmsId(duplicate.messageId, sourceSmsId)
            return IntakeResult(duplicate.messageId, false)
        }
        val snapshot = consent.snapshot()
        val rules = BackendKeywordRules.analyze(messageBody)
        val eligible = AutomaticAnalysisPolicy.eligible(snapshot, receivedAt, historicalImport,
            RemoteTransportGate.mayTransmit(null, false))
        val state = when {
            eligible -> DetectionState.REMOTE_PENDING
            snapshot.enabled && !historicalImport && receivedAt >= snapshot.grantedAt -> DetectionState.REMOTE_UNAVAILABLE
            else -> DetectionState.REMOTE_CONSENT_DISABLED
        }
        val localAssessment = RemoteVerdictPolicy.localFallback(rules.classification.name)
        val message = Message(
            senderHash = senderHash, senderAddress = sender.takeIf { it.isNotBlank() && it != "Unknown" },
            content = messageBody, contentHash = contentHash, sourceSmsId = sourceSmsId,
            alertsEnabled = alertOnDetection, receivedAt = receivedAt,
            riskScore = rules.score.toFloat(), riskLevel = rules.classification.name,
            deterministicRiskLevel = rules.classification.name,
            matchedIndicators = rules.matchedIndicators.joinToString("\n") { it.code },
            hasStrongRuleSignal = rules.classification == RiskLevel.SCAM,
            isFlagged = rules.classification != RiskLevel.SAFE,
            finalClassification = localAssessment,
            processingState = when (state) {
                DetectionState.REMOTE_PENDING -> ProcessingState.AI_QUEUED.name
                DetectionState.REMOTE_UNAVAILABLE -> ProcessingState.REMOTE_UNAVAILABLE.name
                else -> ProcessingState.REMOTE_CONSENT_DISABLED.name
            },
            aiAnalysisStatus = if (state == DetectionState.REMOTE_UNAVAILABLE) AiAnalysisStatus.UNAVAILABLE.name else AiAnalysisStatus.NOT_STARTED.name,
            detectionState = state.name, verdictSource = VerdictSource.LOCAL_RULES.name,
            remoteRequestId = UUID.randomUUID().toString(), consentGeneration = if (eligible) snapshot.generation else 0
        )
        val id = repository.insertMessage(message)
        if (id <= 0) return IntakeResult(repository.getMessageBySourceSmsId(sourceSmsId ?: -1)?.messageId ?: 0, false)
        if (alertOnDetection && rules.classification == RiskLevel.SCAM) {
            NotificationHelper.showScamAlert(app, DetectionResult(
                messageContent = messageBody, riskScore = rules.score.toFloat(),
                riskLevel = RiskLevel.SCAM, isFlagged = true, matchedKeywords = emptyList(),
                gemmaInvoked = false, finalClassification = "SCAM",
                finalRationale = "Strong local keyword indicators. Remote AI has not verified this message."
            ), id)
            repository.updateMessage(message.copy(messageId = id, notificationPosted = true))
        }
        return IntakeResult(id, eligible)
    }

    /** Applies only a completed authenticated server verdict from a future secure transport. */
    suspend fun applyRemoteResult(messageId: Long, generation: Long, classification: String,
                                  confidence: String, rationale: String): Boolean {
        val remoteState = RemoteVerdictPolicy.stateFor(classification) ?: return false
        val current = consent.snapshot()
        val message = repository.getMessageById(messageId) ?: return false
        if (!current.enabled || current.generation != generation || message.consentGeneration != generation ||
            message.processingState !in setOf(ProcessingState.AI_QUEUED.name, ProcessingState.AI_ANALYZING.name)) return false
        val shouldNotify = message.alertsEnabled && classification != "SAFE"
        repository.updateMessage(message.copy(
            notificationPosted = shouldNotify,
            gemmaInvoked = true, gemmaClassification = classification,
            gemmaConfidence = confidence, gemmaRationale = rationale,
            aiAnalysisStatus = AiAnalysisStatus.AVAILABLE.name,
            processingState = ProcessingState.COMPLETED.name,
            detectionState = remoteState.name,
            verdictSource = VerdictSource.REMOTE_GEMMA_RAG.name,
            finalClassification = classification, riskLevel = classification,
            isFlagged = classification != "SAFE"
        ))
        if (classification == "SAFE" && message.notificationPosted) {
            NotificationHelper.cancelAlert(app, messageId)
        } else if (shouldNotify) {
            NotificationHelper.showScamAlert(app, DetectionResult(
                messageContent = message.content, riskScore = message.riskScore,
                riskLevel = RiskLevel.valueOf(classification), isFlagged = true,
                matchedKeywords = emptyList(), gemmaInvoked = true,
                finalClassification = classification, finalRationale = rationale
            ), messageId)
        }
        return true
    }

    suspend fun markAiUnavailable(messageId: Long) {
        val message = repository.getMessageById(messageId) ?: return
        if (message.processingState == ProcessingState.COMPLETED.name) return
        val fallback = RemoteVerdictPolicy.localFallback(message.deterministicRiskLevel)
        repository.updateMessage(message.copy(
            aiAnalysisStatus = AiAnalysisStatus.UNAVAILABLE.name,
            processingState = ProcessingState.REMOTE_UNAVAILABLE.name,
            detectionState = DetectionState.REMOTE_UNAVAILABLE.name,
            verdictSource = VerdictSource.LOCAL_RULES.name,
            finalClassification = fallback,
            gemmaClassification = null, gemmaConfidence = null, gemmaRationale = null
        ))
    }

    suspend fun markConsentDisabled(messageId: Long) {
        val message = repository.getMessageById(messageId) ?: return
        if (message.processingState == ProcessingState.COMPLETED.name) return
        repository.updateMessage(message.copy(
            processingState = ProcessingState.REMOTE_CONSENT_DISABLED.name,
            detectionState = DetectionState.REMOTE_CONSENT_DISABLED.name,
            finalClassification = RemoteVerdictPolicy.localFallback(message.deterministicRiskLevel),
            verdictSource = VerdictSource.LOCAL_RULES.name
        ))
    }

    suspend fun queueAiRetry(@Suppress("UNUSED_PARAMETER") messageId: Long): Boolean = false
}
