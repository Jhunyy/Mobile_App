package com.apcida.smishingdetector.backend.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Deletes an installation, with no SMS body or message identifier in the request. */
internal class ProductionRevocationClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) {
    enum class Result { CONFIRMED, AUTH_REJECTED, CONFIGURATION_REQUIRED, RETRYABLE_FAILURE }

    suspend fun revoke(origin: String, approvedHost: String, token: String): Result =
        withContext(Dispatchers.IO) {
            if (!ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, approvedHost) ||
                URI(origin).host.endsWith(".trycloudflare.com", ignoreCase = true) ||
                !Regex("[A-Za-z0-9_-]{43}").matches(token)) {
                return@withContext Result.CONFIGURATION_REQUIRED
            }
            val request = Request.Builder().url(origin.trimEnd('/') + "/api/v1/installations/me")
                .header("Authorization", "Bearer $token").delete().build()
            try {
                http.newCall(request).execute().use { response ->
                    when (response.code) {
                        204 -> Result.CONFIRMED
                        401, 403 -> Result.AUTH_REJECTED
                        404 -> Result.CONFIGURATION_REQUIRED
                        else -> Result.RETRYABLE_FAILURE
                    }
                }
            } catch (_: IOException) {
                Result.RETRYABLE_FAILURE
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
                Result.RETRYABLE_FAILURE
            }
        }
}
