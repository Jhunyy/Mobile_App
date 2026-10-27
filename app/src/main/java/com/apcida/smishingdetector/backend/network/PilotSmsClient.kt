package com.apcida.smishingdetector.backend.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface PilotResult {
    data class Success(val classification: String, val confidence: String, val rationale: String) : PilotResult
    data class Failure(val message: String) : PilotResult
}

/** Explicit synthetic tests only. Never called from the SMS receiver or worker. */
class PilotSmsClient(
    private val credentials: PilotCredentialSource,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).build(),
) {
    fun analyzeSynthetic(origin: String, approvedHost: String, text: String): PilotResult {
        if (!ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, approvedHost))
            return PilotResult.Failure("Use the approved HTTPS pilot server address.")
        if (text.isBlank() || text.length > 2000)
            return PilotResult.Failure("Enter 1 to 2,000 characters of synthetic SMS text.")
        val token = credentials.get() ?: return PilotResult.Failure("Provision this installation first.")
        if (!Regex("[A-Za-z0-9_-]{43}").matches(token))
            return PilotResult.Failure("Pilot credential is invalid. Provision again.")
        val id = "pilot-" + UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("messageId", id)
            addProperty("sender", "ManualTest")
            addProperty("text", text)
            addProperty("receivedAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
        }
        val request = Request.Builder().url(origin.trimEnd('/') + "/api/sms/analyze")
            .header("Authorization", "Bearer $token")
            .header("x-apcida-pilot-synthetic", "true")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> { credentials.clear(); PilotResult.Failure("Pilot credential rejected or revoked. Re-enroll this installation.") }
                    429 -> PilotResult.Failure("Pilot request limit reached. Try later.")
                    502, 503, 504 -> PilotResult.Failure("AI server is unavailable or timed out.")
                    else -> if (!response.isSuccessful) PilotResult.Failure("Pilot server returned HTTP ${response.code}.")
                        else parse(response.body?.string(), id)
                }
            }
        } catch (_: InterruptedIOException) {
            PilotResult.Failure("Pilot request timed out.")
        } catch (_: IOException) {
            PilotResult.Failure("Pilot server unavailable. Check the secure tunnel and Mac.")
        } catch (_: Exception) {
            PilotResult.Failure("Could not read the pilot server response.")
        }
    }

    private fun parse(raw: String?, id: String): PilotResult = try {
        val value = JsonParser().parse(raw).getAsJsonObject()
        val classification = value.get("classification").getAsString()
        val confidence = value.get("confidence").getAsString()
        val rationale = value.get("rationale").getAsString().trim()
        if (value.get("messageId").getAsString() != id ||
            classification !in setOf("SCAM", "SAFE", "SUSPICIOUS") ||
            confidence !in setOf("HIGH", "MEDIUM", "LOW") ||
            rationale.isEmpty() || rationale.length > 300) PilotResult.Failure("Malformed pilot response.")
        else PilotResult.Success(classification, confidence, rationale)
    } catch (_: Exception) {
        PilotResult.Failure("Malformed pilot response.")
    }
}
