package com.apcida.smishingdetector.evaluation

import android.content.Context
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.detection.ConversationRiskPolicy
import com.apcida.smishingdetector.backend.detection.DetectionDecisionEngine
import com.apcida.smishingdetector.backend.detection.KeywordEngine
import com.apcida.smishingdetector.backend.detection.MessageSignals
import com.apcida.smishingdetector.backend.detection.RiskScorer
import com.apcida.smishingdetector.backend.detection.SenderSignals
import com.apcida.smishingdetector.backend.detection.ThresholdEvaluator
import com.apcida.smishingdetector.backend.gemma.GemmaManager
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.HashUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class RuleEvidence(
    val score: Float,
    val verdict: RiskLevel,
    val indicators: List<String>,
    val strongSignal: Boolean
)

fun interface EvaluationRules { suspend fun analyze(text: String): RuleEvidence }
fun interface EvaluationGemma {
    suspend fun analyze(text: String, indicators: List<String>, history: List<String>,
                        recentScamText: String?): GemmaResult
}

/** Processes rows in order and holds supplied sender history only in memory. */
class EvaluationRunner(
    private val rules: EvaluationRules,
    private val gemma: EvaluationGemma
) {
    companion object {
        fun production(context: Context): EvaluationRunner {
            val appContext = context.applicationContext
            val database = AppDatabase.getInstance(appContext)
            val keywordEngine = KeywordEngine(database.keywordDao())
            val scorer = RiskScorer()
            val thresholds = ThresholdEvaluator()
            val rules = EvaluationRules { text ->
                DatabaseSeeder(appContext, database).seedIfFirstLaunch()
                val matches = keywordEngine.analyze(text)
                val score = scorer.compute(matches)
                RuleEvidence(score, thresholds.evaluate(score),
                    matches.map { it.pattern }.distinct(),
                    MessageSignals.hasStrongRuleSignal(text, matches))
            }
            val inference = EvaluationGemma { text, indicators, history, recentScamText ->
                GemmaManager.getReadyValidator(appContext).validate(
                    text, indicators, history, recentScamText
                )
            }
            return EvaluationRunner(rules, inference)
        }
    }

    suspend fun run(
        inputs: List<EvaluationInput>, metadata: EvaluationMetadata,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): EvaluationReport {
        val records = mutableListOf<EvaluationRecord>()
        val historyBySender = mutableMapOf<String, MutableList<Message>>()
        inputs.forEachIndexed { index, input ->
            val senderKey = input.senderAddress?.let(HashUtil::hashSender)
            val context = senderKey != null && input.receivedAt != null
            val contextMode = if (context) "SENDER_CONTEXT" else "MESSAGE_ONLY"
            if (input.inputError != null || input.verifiedLabel == null) {
                records += EvaluationRecord(input.messageId, input.verifiedLabel,
                    messageText = input.messageText,
                    contextMode = contextMode,
                    error = input.inputError ?: "Missing verified label.")
                onProgress(index + 1, inputs.size)
                return@forEachIndexed
            }
            try {
                val rule = rules.analyze(input.messageText)
                val senderHistory = if (context) historyBySender[senderKey].orEmpty()
                    else emptyList()
                require(!context || senderHistory.lastOrNull()?.receivedAt?.let {
                    it <= input.receivedAt!!
                } != false) { "Sender rows must be ordered by received_at." }
                val previous = if (context) senderHistory
                    .filter { it.receivedAt <= input.receivedAt!! &&
                        it.receivedAt >= input.receivedAt!! - ConversationRiskPolicy.CONTEXT_WINDOW_MILLIS }
                else emptyList()
                val recent = previous.filter {
                    it.receivedAt >= input.receivedAt!! - ConversationRiskPolicy.WARNING_WINDOW_MILLIS
                }
                val historyPoints = if (context) ConversationRiskPolicy.historyPoints(recent,
                    input.receivedAt!!) else 0
                val recentScamText = recent.asReversed().firstOrNull {
                    ConversationRiskPolicy.historyPoints(listOf(it), input.receivedAt!!) == 25
                }?.content
                val recentText = previous.takeLast(ConversationRiskPolicy.MAX_CONTEXT_MESSAGES)
                    .map { it.content }
                val started = System.nanoTime()
                var result = GemmaResult.fallback()
                repeat(3) { attempt ->
                    if (!result.isSuccessful) {
                        result = try {
                            withTimeoutOrNull(Constants.GEMMA_TIMEOUT_MILLIS) {
                                gemma.analyze(input.messageText, rule.indicators, recentText,
                                    recentScamText)
                            } ?: GemmaResult.fallback("Gemma inference timed out.")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            GemmaResult.fallback(error.message ?: "Gemma inference failed.")
                        }
                    }
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val senderPoints = SenderSignals.points(input.senderAddress, input.messageText)
                val outcome = DetectionDecisionEngine.resolve(
                    body = input.messageText,
                    ruleScore = rule.score,
                    ruleVerdict = rule.verdict,
                    hasStrongRuleSignal = rule.strongSignal,
                    senderPoints = senderPoints,
                    isSavedContact = input.isSavedContact,
                    historyPoints = historyPoints,
                    gemmaResult = result
                )
                val evidence = EvidenceValidator.assess(input.messageText, result)
                val experimentalResult = if (result.isSuccessful &&
                    evidence.tier in setOf(EvidenceTier.AMBIGUOUS, EvidenceTier.UNSUPPORTED)) {
                    GemmaResult("LEGITIMATE", "LOW", "Experimental evidence gate removed unsupported SCAM evidence.")
                } else result
                val experimentalBase = if (experimentalResult == result) outcome else
                    DetectionDecisionEngine.resolve(
                        body = input.messageText,
                        ruleScore = rule.score,
                        ruleVerdict = rule.verdict,
                        hasStrongRuleSignal = rule.strongSignal,
                        senderPoints = senderPoints,
                        isSavedContact = input.isSavedContact,
                        historyPoints = historyPoints,
                        gemmaResult = experimentalResult
                    )
                val experimentalVerdict = if (evidence.tier == EvidenceTier.AMBIGUOUS &&
                    experimentalBase.finalVerdict == RiskLevel.SAFE) RiskLevel.SUSPICIOUS
                    else experimentalBase.finalVerdict
                records += EvaluationRecord(
                    messageId = input.messageId,
                    verifiedLabel = input.verifiedLabel,
                    messageText = input.messageText,
                    ruleScore = rule.score,
                    ruleVerdict = rule.verdict,
                    matchedIndicators = rule.indicators,
                    gemmaClassification = result.classification.takeIf { result.isSuccessful },
                    gemmaConfidence = result.confidence.takeIf { result.isSuccessful },
                    gemmaReason = if (result.isSuccessful) result.rationale else result.errorMessage,
                    gemmaInferenceStatus = if (result.isSuccessful) "AVAILABLE" else "UNAVAILABLE",
                    finalVerdict = outcome.finalVerdict,
                    evidenceTier = evidence.tier.name,
                    supportedEvidenceCues = evidence.supportedCues,
                    unsupportedReasonClaims = evidence.unsupportedReasonClaims,
                    experimentalModelClassification = evidence.experimentalClassification,
                    experimentalFinalVerdict = experimentalVerdict,
                    inferenceTimeMs = elapsed,
                    contextMode = contextMode,
                    error = null
                )
                if (context) {
                    historyBySender.getOrPut(senderKey!!) { mutableListOf() }.add(
                        Message(content = input.messageText, receivedAt = input.receivedAt!!,
                            senderAddress = input.senderAddress,
                            riskScore = rule.score,
                            finalClassification = outcome.finalVerdict.name,
                            gemmaClassification = result.classification.takeIf { result.isSuccessful },
                            gemmaConfidence = result.confidence.takeIf { result.isSuccessful },
                            aiAnalysisStatus = if (result.isSuccessful) "AVAILABLE" else "UNAVAILABLE",
                            aiRiskPoints = outcome.points.aiPoints)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                records += EvaluationRecord(input.messageId, input.verifiedLabel,
                    messageText = input.messageText,
                    contextMode = contextMode, error = error.message ?: "Evaluation failed.")
            }
            onProgress(index + 1, inputs.size)
        }
        return EvaluationReport(metadata, records)
    }
}
