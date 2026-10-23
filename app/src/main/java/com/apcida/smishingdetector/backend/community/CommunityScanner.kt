package com.apcida.smishingdetector.backend.community

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.detection.FinalVerdictResolver
import com.apcida.smishingdetector.backend.detection.KeywordEngine
import com.apcida.smishingdetector.backend.detection.MessageSignals
import com.apcida.smishingdetector.backend.detection.RiskScorer
import com.apcida.smishingdetector.backend.detection.ThresholdEvaluator
import com.apcida.smishingdetector.backend.gemma.GemmaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class ContributionCandidate(val id: String, val text: String, val predictedCategory: String)
data class CommunityScanProgress(
    val examined: Int,
    val analyzed: Int,
    val candidates: Int,
    val analyzing: Boolean = false
)
data class CommunityScanResult(
    val candidates: List<ContributionCandidate>,
    val progress: CommunityScanProgress,
    val stopReason: StopReason
) {
    enum class StopReason { FINISHED, TIME_LIMIT, MESSAGE_LIMIT, ANALYSIS_LIMIT, MODEL_FAILURE }
}

/** Reads only SMS bodies on device; no sender addresses are queried or stored. */
class CommunityScanner(private val context: Context) {
    companion object {
        private const val SCAN_PREFS = "community_scan"
        private const val NEXT_OFFSET = "next_sms_offset"
        private const val MAX_SCAN_MILLIS = 180_000L
        private const val MAX_INFERENCE_MILLIS = 60_000L
        private const val MAX_EXAMINED = 1_000
        private const val MAX_ANALYZED = 20
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private val scanPrefs = context.getSharedPreferences(SCAN_PREFS, Context.MODE_PRIVATE)

    suspend fun scan(
        scamRemaining: Int,
        safeRemaining: Int,
        excludedIds: Set<String>,
        onProgress: suspend (CommunityScanProgress) -> Unit = {}
    ): CommunityScanResult =
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + MAX_SCAN_MILLIS
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("SMS access is required to scan existing messages.")
            }
            val database = AppDatabase.getInstance(context)
            DatabaseSeeder(context, database).seedIfFirstLaunch()
            val keywords = KeywordEngine(database.keywordDao())
            val scorer = RiskScorer()
            val thresholds = ThresholdEvaluator()
            val validator = GemmaManager.getReadyValidator(context)
            if (!validator.isReady()) throw IllegalStateException("On-device Gemma model is unavailable.")
            val found = mutableListOf<ContributionCandidate>()
            val seenIds = excludedIds.toMutableSet()
            val seenTemplates = mutableSetOf<String>()
            var scamCount = 0
            var safeCount = 0
            var examined = 0
            var analyzed = 0
            var consecutiveFailures = 0
            var stopReason = CommunityScanResult.StopReason.FINISHED
            suspend fun reportProgress(analyzing: Boolean = false) =
                onProgress(CommunityScanProgress(examined, analyzed, found.size, analyzing))
            reportProgress()
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY), null, null, "${Telephony.Sms.DATE} DESC"
            ) ?: return@withContext CommunityScanResult(
                emptyList(), CommunityScanProgress(0, 0, 0), stopReason
            )
            cursor.use {
                val startOffset = scanPrefs.getInt(NEXT_OFFSET, 0).coerceAtLeast(0)
                if (!it.moveToPosition(startOffset - 1)) it.moveToPosition(-1)
                val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (scamCount < scamRemaining || safeCount < safeRemaining) {
                    coroutineContext.ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        stopReason = CommunityScanResult.StopReason.TIME_LIMIT
                        break
                    }
                    if (examined >= MAX_EXAMINED) {
                        stopReason = CommunityScanResult.StopReason.MESSAGE_LIMIT
                        break
                    }
                    if (analyzed >= MAX_ANALYZED) {
                        stopReason = CommunityScanResult.StopReason.ANALYSIS_LIMIT
                        break
                    }
                    if (!it.moveToNext()) break
                    examined++
                    if (examined % 25 == 0) reportProgress()
                    val body = it.getString(bodyIndex) ?: continue
                    if (!CommunityPolicy.eligible(body)) continue
                    val id = CommunityPolicy.fingerprint(body)
                    if (!seenIds.add(id) || !seenTemplates.add(CommunityPolicy.template(body))) continue
                    val matches = keywords.analyze(body)
                    val ruleLevel = thresholds.evaluate(scorer.compute(matches))
                    val remainingTime = deadline - SystemClock.elapsedRealtime()
                    if (remainingTime <= 0) {
                        stopReason = CommunityScanResult.StopReason.TIME_LIMIT
                        break
                    }
                    reportProgress(analyzing = true)
                    val gemma = withTimeoutOrNull(minOf(MAX_INFERENCE_MILLIS, remainingTime)) {
                        validator.validate(body, matches.map { keyword -> keyword.pattern })
                    }
                    analyzed++
                    reportProgress()
                    if (gemma?.isSuccessful != true) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            stopReason = CommunityScanResult.StopReason.MODEL_FAILURE
                            break
                        }
                        continue
                    }
                    consecutiveFailures = 0
                    val verdict = FinalVerdictResolver.resolve(
                        ruleLevel, gemma, isSavedContact = false,
                        hasStrongRuleSignal = MessageSignals.hasStrongRuleSignal(body, matches)
                    ).riskLevel
                    val category = CommunityPolicy.candidateCategory(ruleLevel, gemma, verdict,
                        scamRemaining - scamCount, safeRemaining - safeCount) ?: continue
                    val redacted = CommunityPolicy.redact(body)
                    if (redacted.isBlank()) continue
                    found += ContributionCandidate(id, redacted, category)
                    if (category == "SCAM") scamCount++ else safeCount++
                    reportProgress()
                }
                val nextOffset = if (stopReason == CommunityScanResult.StopReason.FINISHED ||
                    stopReason == CommunityScanResult.StopReason.MODEL_FAILURE) 0 else startOffset + examined
                scanPrefs.edit().putInt(NEXT_OFFSET, nextOffset).apply()
            }
            CommunityScanResult(found, CommunityScanProgress(examined, analyzed, found.size), stopReason)
        }
}
