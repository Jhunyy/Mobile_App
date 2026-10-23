package com.apcida.smishingdetector.backend.community

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import com.apcida.smishingdetector.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class ContributionCandidate(val id: String, val text: String, val predictedCategory: String)

/** Reads only SMS bodies on device; no sender addresses are queried or stored. */
class CommunityScanner(private val context: Context) {
    suspend fun scan(scamRemaining: Int, safeRemaining: Int, excludedIds: Set<String>): List<ContributionCandidate> =
        withContext(Dispatchers.IO) {
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
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY), null, null, "${Telephony.Sms.DATE} DESC"
            ) ?: return@withContext emptyList()
            cursor.use {
                val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (it.moveToNext() && (scamCount < scamRemaining || safeCount < safeRemaining)) {
                    coroutineContext.ensureActive()
                    val body = it.getString(bodyIndex) ?: continue
                    if (!CommunityPolicy.eligible(body)) continue
                    val id = CommunityPolicy.fingerprint(body)
                    if (!seenIds.add(id) || !seenTemplates.add(CommunityPolicy.template(body))) continue
                    val matches = keywords.analyze(body)
                    val ruleLevel = thresholds.evaluate(scorer.compute(matches))
                    val gemma = withTimeoutOrNull(Constants.GEMMA_TIMEOUT_MILLIS) {
                        validator.validate(body, matches.map { keyword -> keyword.pattern })
                    } ?: continue
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
                }
            }
            found
        }
}
