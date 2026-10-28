package com.apcida.smishingdetector.controller

import android.content.Context
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.consent.AutomaticAnalysisPolicy
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.detection.BackendKeywordRules
import com.apcida.smishingdetector.backend.detection.DuplicatePolicy
import com.apcida.smishingdetector.backend.remote.ProductionRemoteRuntime
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Incoming SMS: persist and warn locally first. Automatic transport remains security-gated. */
class SmsController(context: Context) {
    companion object { private val intakeMutex = Mutex() }
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
    ): IntakeResult = intakeMutex.withLock {
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
            return@withLock IntakeResult(duplicate.messageId, false)
        }
        val snapshot = consent.snapshot()
        val rules = BackendKeywordRules.analyze(messageBody)
        val eligible = AutomaticAnalysisPolicy.eligible(snapshot, receivedAt, historicalImport,
            ProductionRemoteRuntime.canSchedule(app, snapshot))
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
        if (id <= 0) return@withLock IntakeResult(repository.getMessageBySourceSmsId(sourceSmsId ?: -1)?.messageId ?: 0, false)
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

    /** Atomic claim before any future remote request; duplicate work cannot analyze twice. */
    suspend fun claimRemoteAnalysis(messageId: Long, generation: Long): Boolean =
        repository.claimRemoteAnalysis(messageId, generation)

    suspend fun markClaimedAiUnavailable(messageId: Long, generation: Long): Boolean =
        repository.failClaimedRemoteAnalysis(messageId, generation)

    suspend fun markClaimedConsentDisabled(messageId: Long, generation: Long): Boolean =
        repository.disableClaimedRemoteAnalysis(messageId, generation)

    /** Applies only a completed authenticated server verdict from a future secure transport. */
    suspend fun applyRemoteResult(messageId: Long, generation: Long, classification: String,
                                  confidence: String, rationale: String): Boolean {
        val remoteState = RemoteVerdictPolicy.stateFor(classification) ?: return false
        if (confidence !in setOf("HIGH", "MEDIUM", "LOW") || rationale.isBlank() || rationale.length > 300) return false
        val current = consent.snapshot()
        val message = repository.getMessageById(messageId) ?: return false
        if (!current.enabled || current.generation != generation || message.consentGeneration != generation ||
            message.processingState != ProcessingState.AI_ANALYZING.name) return false
        val shouldNotify = message.alertsEnabled && classification != "SAFE"
        if (!repository.completeRemoteAnalysis(messageId, generation, classification,
                confidence, rationale, remoteState.name)) return false
        // Revocation can race with a finished request. Never post a new alert after revocation.
        val after = consent.snapshot()
        if (!after.enabled || after.generation != generation) return true
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

    suspend fun markAiUnavailable(messageId: Long): Boolean {
        val message = repository.getMessageById(messageId) ?: return false
        return repository.failPendingRemoteAnalysis(messageId, message.consentGeneration)
    }

    suspend fun markConsentDisabled(messageId: Long) {
        val message = repository.getMessageById(messageId) ?: return
        repository.disablePendingRemoteAnalysis(messageId, message.consentGeneration)
    }

    suspend fun queueAiRetry(@Suppress("UNUSED_PARAMETER") messageId: Long): Boolean = false
}
