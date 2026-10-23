package com.apcida.smishingdetector.backend.community

import android.content.Context
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.util.Constants
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
    private val participantId: String by lazy {
        prefs.getString("participant_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("participant_id", it).apply()
        }
    }
    private val api: CommunityApi by lazy {
        Retrofit.Builder().baseUrl(Constants.BASE_URL)
            .client(OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(CommunityApi::class.java)
    }

    fun cachedProgress() = ContributionProgress(prefs.getInt("scam", 0), prefs.getInt("safe", 0))
    fun acceptedIds(): Set<String> = prefs.getStringSet("accepted_ids", emptySet())?.toSet() ?: emptySet()
    fun hasPending(): Boolean = pendingFile.exists()
    fun discardPending() { pendingFile.delete() }
    fun configured(): Boolean = Constants.BASE_URL.startsWith("https://") &&
        Constants.BASE_URL.endsWith("/") && BuildConfig.COMMUNITY_API_KEY.isNotBlank()

    suspend fun refresh(): ContributionProgress = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext cachedProgress()
        val response = api.progress(BuildConfig.COMMUNITY_API_KEY, participantId)
        if (!response.isSuccessful) throw IllegalStateException("Progress request failed (${response.code()}).")
        val progress = response.body() ?: throw IllegalStateException("Missing progress response.")
        cache(progress)
        progress
    }

    suspend fun queue(batch: ContributionBatch) = withContext(Dispatchers.IO) {
        check(!pendingFile.exists()) { "A previously approved contribution is still pending." }
        pendingFile.writeText(gson.toJson(batch))
    }

    fun makeBatch(items: List<ContributionItem>) = ContributionBatch(
        UUID.randomUUID().toString(), participantId, System.currentTimeMillis(), items
    )

    suspend fun submitPending(): ContributionProgress = withContext(Dispatchers.IO) {
        if (!configured()) throw IllegalStateException("The community server is not configured.")
        if (!pendingFile.exists()) return@withContext refresh()
        val batch = gson.fromJson(pendingFile.readText(), ContributionBatch::class.java)
        val response = api.submit(BuildConfig.COMMUNITY_API_KEY, batch)
        if (!response.isSuccessful) throw IllegalStateException("Contribution upload failed (${response.code()}).")
        val result = response.body() ?: throw IllegalStateException("Missing contribution response.")
        val accepted = result.acceptedClientIds.toSet()
        val remaining = CommunitySubmissionPolicy.remaining(batch, result)
        cache(result.progress)
        prefs.edit().putStringSet("accepted_ids", acceptedIds() + accepted).apply()
        if (remaining.isEmpty()) pendingFile.delete()
        else pendingFile.writeText(gson.toJson(batch.copy(items = remaining)))
        result.progress
    }

    private fun cache(progress: ContributionProgress) {
        require(progress.scamAccepted in 0..20 && progress.safeAccepted in 0..20)
        prefs.edit().putInt("scam", progress.scamAccepted).putInt("safe", progress.safeAccepted).apply()
    }
}
