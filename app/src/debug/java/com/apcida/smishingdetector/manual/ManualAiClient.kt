package com.apcida.smishingdetector.manual

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.TimeUnit

enum class RemoteClassification { SCAM, SAFE, SUSPICIOUS }
enum class RemoteConfidence { HIGH, MEDIUM, LOW }

sealed interface ManualAiResult {
    data class Success(
        val classification: RemoteClassification,
        val confidence: RemoteConfidence,
        val rationale: String,
    ) : ManualAiResult

    data class Failure(val message: String) : ManualAiResult
}

/** Used only by the debug manual screen. Construction never starts a request. */
class ManualAiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(270, TimeUnit.SECONDS)
        .build(),
) {
    fun analyze(
        baseUrl: String,
        apiKey: String,
        smsText: String,
        messageId: String,
        receivedAt: String,
    ): ManualAiResult {
        val origin = validatedOrigin(baseUrl)
            ?: return ManualAiResult.Failure("Enter a private LAN server address, such as http://192.168.1.23:3000.")
        if (apiKey.isBlank()) return ManualAiResult.Failure("Enter the development API key.")
        if (smsText.isBlank() || smsText.length > 2000) {
            return ManualAiResult.Failure("Enter a synthetic SMS message of 1 to 2,000 characters.")
        }
        val payload = JsonObject().apply {
            addProperty("messageId", messageId)
            addProperty("sender", "ManualTest")
            addProperty("text", smsText)
            addProperty("receivedAt", receivedAt)
        }
        val request = Request.Builder()
            .url("$origin/api/sms/analyze")
            .header("x-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ManualAiResult.Failure(httpError(response.code))
                val body = response.body?.string() ?: return ManualAiResult.Failure("AI server returned a malformed response.")
                parseSuccess(body, messageId)
            }
        } catch (_: InterruptedIOException) {
            ManualAiResult.Failure("AI server request timed out. Check that the Mac is awake and try again.")
        } catch (_: IOException) {
            ManualAiResult.Failure("AI server is unavailable. Check the Mac, Wi-Fi, and server address.")
        } catch (_: Exception) {
            ManualAiResult.Failure("Could not read the AI server response.")
        }
    }

    private fun parseSuccess(json: String, expectedId: String): ManualAiResult = try {
        val objectValue = JsonParser().parse(json).getAsJsonObject()
        val id = objectValue.get("messageId")?.getAsString()
        val classification = RemoteClassification.valueOf(objectValue.get("classification")?.getAsString() ?: "")
        val confidence = RemoteConfidence.valueOf(objectValue.get("confidence")?.getAsString() ?: "")
        val rationale = objectValue.get("rationale")?.getAsString()?.trim().orEmpty()
        if (id != expectedId || rationale.isEmpty() || rationale.length > 300) {
            ManualAiResult.Failure("AI server returned a malformed response.")
        } else {
            ManualAiResult.Success(classification, confidence, rationale)
        }
    } catch (_: Exception) {
        ManualAiResult.Failure("AI server returned a malformed response.")
    }

    private fun httpError(status: Int): String = when (status) {
        401 -> "Invalid API key. Check the development key on this device."
        400, 413 -> "The AI server rejected this test message."
        429 -> "AI server rate limit reached. Wait and try again."
        502, 503 -> "AI server or Gemma is unavailable. Check the Mac services."
        504 -> "AI server timed out. Check that the Mac is awake and try again."
        else -> "AI server returned HTTP $status."
    }

    private fun validatedOrigin(input: String): String? {
        val uri = try { URI(input.trim()) } catch (_: Exception) { return null }
        if (uri.scheme != "http" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
            || (uri.rawPath != null && uri.rawPath.isNotEmpty() && uri.rawPath != "/")
            || uri.port !in 1..65535) return null
        val octets = uri.host?.split('.')?.map { it.toIntOrNull() } ?: return null
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return null
        val first = octets[0]!!
        val second = octets[1]!!
        val privateLan = first == 10 || (first == 172 && second in 16..31)
            || (first == 192 && second == 168)
        // 127.0.0.1 is useful only with an explicit adb reverse USB tunnel.
        if (!privateLan && uri.host != "127.0.0.1") return null
        return "http://${uri.host}:${uri.port}"
    }
}
