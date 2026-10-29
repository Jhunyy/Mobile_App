package com.apcida.smishingdetector.backend.remote

import com.apcida.smishingdetector.backend.consent.AutomaticAnalysisPolicy
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialSource
import com.apcida.smishingdetector.backend.network.ProductionCredentialSource
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import com.apcida.smishingdetector.backend.network.ProductionTiming
import com.apcida.smishingdetector.backend.network.executeCancellable
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Future production-only protocol. No receiver or worker constructs this transport. */
internal class ProductionSmsTransport(
    private val origin: String,
    private val approvedHost: String,
    private val credentials: ProductionCredentialSource,
    private val accessCredentials: CloudflareAccessCredentialSource,
    private val requirements: () -> RemoteTransportGate.Requirements,
    private val gate: RemoteAuthorizationGate = ProductionRemoteAuthorizationGate,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ProductionTiming.CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(ProductionTiming.READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(ProductionTiming.CALL_SECONDS, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).build(),
) : RemoteAnalysisTransport {
    override suspend fun analyze(candidate: RemoteSmsCandidate): RemoteAnalysisReply = withContext(Dispatchers.IO) {
        val current = requirements()
        if (!gate.allows(current) || current.endpoint != origin ||
            current.approvedHost != approvedHost ||
            current.consent.generation != candidate.consentGeneration ||
            !AutomaticAnalysisPolicy.eligible(current.consent, candidate.receivedAt,
                candidate.historicalImport, true) ||
            !ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, approvedHost) ||
            URI(origin).host.endsWith(".trycloudflare.com", ignoreCase = true)) {
            return@withContext RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE)
        }
        val token = credentials.get()?.takeIf { Regex("[A-Za-z0-9_-]{43}").matches(it) }
            ?: return@withContext RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)
        val access = accessCredentials.get()
            ?: return@withContext RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)
        if (candidate.text.length > 2000 || candidate.sender.length > 100 ||
            !Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}").matches(candidate.requestId)) {
            return@withContext RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.INVALID_RESPONSE)
        }
        val payload = JsonObject().apply {
            addProperty("messageId", candidate.requestId)
            addProperty("sender", candidate.sender)
            addProperty("text", candidate.text)
            addProperty("receivedAt", Instant.ofEpochMilli(candidate.receivedAt).toString())
        }
        val request = Request.Builder().url(origin.trimEnd('/') + "/api/v1/sms/analyze")
            .header("Authorization", "Bearer $token")
            .header("CF-Access-Client-Id", access.clientId)
            .header("CF-Access-Client-Secret", access.clientSecret)
            .header("Idempotency-Key", candidate.requestId)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            http.executeCancellable(request) { response ->
                when (response.code) {
                    401, 403 -> {
                        credentials.quarantine()
                        RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)
                    }
                    408, 504 -> RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.TIMEOUT)
                    429, 500, 502, 503 -> RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE)
                    else -> if (!response.isSuccessful)
                        RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE)
                    else parse(response.body?.string(), candidate.requestId)
                }
            }
        } catch (_: InterruptedIOException) {
            RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.TIMEOUT)
        } catch (_: IOException) {
            RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.INVALID_RESPONSE)
        }
    }

    private fun parse(raw: String?, expectedId: String): RemoteAnalysisReply = try {
        val value = JsonParser().parse(raw).getAsJsonObject()
        val classification = value.get("classification").getAsString()
        val confidence = value.get("confidence").getAsString()
        val rationale = value.get("rationale").getAsString().trim()
        if (value.get("messageId").getAsString() != expectedId ||
            classification !in setOf("SCAM", "SAFE", "SUSPICIOUS") ||
            confidence !in setOf("HIGH", "MEDIUM", "LOW") ||
            rationale.isEmpty() || rationale.length > 300)
            RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.INVALID_RESPONSE)
        else RemoteAnalysisReply.Verified(classification, confidence, rationale)
    } catch (_: Exception) {
        RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.INVALID_RESPONSE)
    }
}
