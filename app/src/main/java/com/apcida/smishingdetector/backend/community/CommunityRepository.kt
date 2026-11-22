package com.apcida.smishingdetector.backend.community

import android.content.Context
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Accepted counts are cached only from server responses; pending text stays in no-backup storage. */
class CommunityRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("community_progress", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val pendingFile = File(context.noBackupFilesDir, "community_pending.json")
    private fun participantId(): String =
        prefs.getString("participant_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("participant_id", it).apply()
        }
    private val credentials = ProductionCredentialStore(context)
    private val accessCredentials = CloudflareAccessCredentialStore(context)
    private fun authorization(): Triple<String, String, String> {
        val token = credentials.get() ?: throw IllegalStateException("Enroll this phone before sharing.")
        val access = accessCredentials.get() ?: throw IllegalStateException("Phone access is unavailable.")
        return Triple("Bearer $token", access.clientId, access.clientSecret)
    }
    private val api: CommunityApi by lazy {
        Retrofit.Builder().baseUrl("https://${BuildConfig.PRODUCTION_APPROVED_HOST}/")
            .client(OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(CommunityApi::class.java)
    }

    fun cachedProgress() = ContributionProgress(prefs.getInt("total", 0))
    fun acceptedIds(): Set<String> = prefs.getStringSet("accepted_ids", emptySet())?.toSet() ?: emptySet()
    fun hasPending(): Boolean = pendingFile.exists()
    fun discardPending() { pendingFile.delete() }
    fun configured(): Boolean = BuildConfig.INVITE_SETUP_ENABLED &&
        BuildConfig.PRODUCTION_APPROVED_HOST == "sms.apcida.stream" &&
        credentials.get() != null && accessCredentials.get() != null

    suspend fun refresh(): ContributionProgress = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext cachedProgress()
        val auth = authorization()
        val response = api.progress(auth.first, auth.second, auth.third, participantId())
        if (!response.isSuccessful) throw IllegalStateException("Progress request failed (${response.code()}).")
        val progress = response.body() ?: throw IllegalStateException("Missing progress response.")
        cache(progress)
        progress
    }

    suspend fun queue(batch: ContributionBatch) = withContext(Dispatchers.IO) {
        check(!pendingFile.exists()) { "A previously approved contribution is still pending." }
        pendingFile.writeText(gson.toJson(batch))
    }

    fun makeBatch(items: List<ContributionItem>, examinedCount: Int, offeredCount: Int) = ContributionBatch(
        UUID.randomUUID().toString(), participantId(), System.currentTimeMillis(),
        "STUDY_EVALUATION_ONLY", examinedCount, offeredCount,
        "RECENT_INBOX_PARTICIPANT_REVIEW", items
    )

    suspend fun submitPending(): ContributionProgress = withContext(Dispatchers.IO) {
        if (!configured()) throw IllegalStateException("Phone access or research service is unavailable.")
        if (!pendingFile.exists()) return@withContext refresh()
        val stored = pendingFile.readText()
        var batch = gson.fromJson(stored, ContributionBatch::class.java)
        if (CommunitySubmissionPolicy.hasLegacyParticipantLabels(stored)) {
            // An older approved batch used participant labels. Give its unlabeled
            // retry a new idempotency key while keeping the reviewed text and IDs.
            batch = batch.copy(batchId = UUID.randomUUID().toString(),
                submittedAt = System.currentTimeMillis())
            pendingFile.writeText(gson.toJson(batch))
        }
        val auth = authorization()
        val response = api.submit(auth.first, auth.second, auth.third, batch)
        if (!response.isSuccessful) throw IllegalStateException("Contribution upload failed (${response.code()}).")
        val result = response.body() ?: throw IllegalStateException("Missing contribution response.")
        val accepted = result.acceptedClientIds.toSet()
        val remaining = CommunitySubmissionPolicy.remaining(batch, result)
        check(remaining.isEmpty()) { "Research server response was incomplete; retry the same batch." }
        cache(result.progress)
        prefs.edit().putStringSet("accepted_ids", acceptedIds() + accepted).apply()
        pendingFile.delete()
        result.progress
    }

    suspend fun withdraw() = withContext(Dispatchers.IO) {
        if (!configured()) throw IllegalStateException("Phone access is unavailable.")
        val auth = authorization()
        val response = api.withdraw(auth.first, auth.second, auth.third)
        if (response.code() != 204) throw IllegalStateException("Withdrawal failed (${response.code()}).")
        pendingFile.delete()
        prefs.edit().clear().commit()
    }

    private fun cache(progress: ContributionProgress) {
        require(progress.totalAccepted in 0..40)
        prefs.edit().remove("scam").remove("safe")
            .putInt("total", progress.totalAccepted).apply()
    }
}
