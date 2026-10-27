package com.apcida.smishingdetector.backend.community

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.database.DatabaseSeeder
import com.apcida.smishingdetector.backend.detection.BackendKeywordRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    val stopReason: StopReason,
    val willResume: Boolean = false
) {
    enum class StopReason { FINISHED, TIME_LIMIT, MESSAGE_LIMIT, ANALYSIS_LIMIT, MODEL_FAILURE }
}

/** Reads recent SMS bodies on device; no sender addresses are queried or stored. */
class CommunityScanner(private val context: Context) {
    companion object {
        private const val TAG = "CommunityScanner"
        private const val SCAN_PREFS = "community_scan"
        private const val LAST_SMS_DATE = "last_sms_date"
        private const val LAST_SMS_ID = "last_sms_id"
        private const val LEGACY_NEXT_OFFSET = "next_sms_offset"
        private const val SCAN_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_SCAN_MILLIS = 180_000L
        private const val MAX_INFERENCE_MILLIS = 60_000L
        private const val MAX_EXAMINED = 1_000
        private const val MAX_ANALYZED = 20
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private data class ScanMarker(val date: Long, val id: Long)

    private val scanPrefs = context.getSharedPreferences(SCAN_PREFS, Context.MODE_PRIVATE)

    suspend fun scan(
        scamRemaining: Int,
        safeRemaining: Int,
        excludedIds: Set<String>,
        onProgress: suspend (CommunityScanProgress) -> Unit = {}
    ): CommunityScanResult =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("SMS access is required to scan existing messages.")
            }
            val deadline = SystemClock.elapsedRealtime() + MAX_SCAN_MILLIS
            val found = mutableListOf<ContributionCandidate>()
            val seenIds = excludedIds.toMutableSet()
            val seenTemplates = mutableSetOf<String>()
            var scamCount = 0
            var safeCount = 0
            var examined = 0
            var analyzed = 0
            var stopReason = CommunityScanResult.StopReason.FINISHED
            suspend fun reportProgress(analyzing: Boolean = false) =
                onProgress(CommunityScanProgress(examined, analyzed, found.size, analyzing))
            reportProgress()
            val scanStartedAt = System.currentTimeMillis()
            val cutoff = scanStartedAt - SCAN_WINDOW_MILLIS
            val savedDate = scanPrefs.getLong(LAST_SMS_DATE, 0L)
            val savedId = scanPrefs.getLong(LAST_SMS_ID, 0L)
            val resumeMarker = if (savedId > 0 && savedDate in cutoff..scanStartedAt) {
                ScanMarker(savedDate, savedId)
            } else null
            val selection = buildString {
                append("${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?")
                if (resumeMarker != null) {
                    append(" AND (${Telephony.Sms.DATE} < ? OR ")
                    append("(${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))")
                }
            }
            val selectionArgs = mutableListOf(cutoff.toString(), scanStartedAt.toString()).apply {
                if (resumeMarker != null) {
                    add(resumeMarker.date.toString())
                    add(resumeMarker.date.toString())
                    add(resumeMarker.id.toString())
                }
            }.toTypedArray()
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.BODY),
                selection, selectionArgs, "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC"
            ) ?: return@withContext CommunityScanResult(
                emptyList(), CommunityScanProgress(0, 0, 0), stopReason
            )
            var lastChecked = resumeMarker
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
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
                    val previousMarker = lastChecked
                    lastChecked = ScanMarker(it.getLong(dateIndex), it.getLong(idIndex))
                    examined++
                    if (examined % 25 == 0) reportProgress()
                    val body = it.getString(bodyIndex) ?: continue
                    if (!CommunityPolicy.eligible(body)) continue
                    val id = CommunityPolicy.fingerprint(body)
                    if (!seenIds.add(id) || !seenTemplates.add(CommunityPolicy.template(body))) continue
                    val rules = BackendKeywordRules.analyze(body)
                    analyzed++
                    val category = when {
                        rules.classification.name == "SCAM" && scamCount < scamRemaining -> "SCAM"
                        rules.classification.name == "SAFE" && safeCount < safeRemaining -> "UNVERIFIED"
                        else -> null
                    } ?: continue
                    val redacted = CommunityPolicy.redact(body)
                    if (redacted.isBlank()) continue
                    found += ContributionCandidate(id, redacted, category)
                    if (category == "SCAM") scamCount++ else safeCount++
                    reportProgress()
                }
            }
            val markerToSave = lastChecked
            val willResume = markerToSave != null &&
                stopReason != CommunityScanResult.StopReason.FINISHED &&
                stopReason != CommunityScanResult.StopReason.MODEL_FAILURE
            scanPrefs.edit().remove(LEGACY_NEXT_OFFSET).apply {
                if (willResume) {
                    putLong(LAST_SMS_DATE, markerToSave.date)
                    putLong(LAST_SMS_ID, markerToSave.id)
                } else {
                    remove(LAST_SMS_DATE)
                    remove(LAST_SMS_ID)
                }
            }.apply()
            CommunityScanResult(found, CommunityScanProgress(examined, analyzed, found.size),
                stopReason, willResume)
        }
}
