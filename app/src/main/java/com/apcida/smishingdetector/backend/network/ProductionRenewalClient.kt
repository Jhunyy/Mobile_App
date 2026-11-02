package com.apcida.smishingdetector.backend.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Renews a phone's service lifetime. No SMS or message metadata enters this request. */
internal class ProductionRenewalClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) {
    suspend fun renew(host: String, bearer: String,
                      access: CloudflareAccessCredential): Long? = withContext(Dispatchers.IO) {
        if (!ProductionEndpointPolicy.isApprovedHttpsOrigin("https://$host", host) ||
            host.endsWith(".trycloudflare.com", ignoreCase = true) ||
            !Regex("[A-Za-z0-9_-]{43}").matches(bearer)) return@withContext null
        val request = Request.Builder()
            .url("https://$host/api/v1/installations/me/renew")
            .header("Authorization", "Bearer $bearer")
            .header("CF-Access-Client-Id", access.clientId)
            .header("CF-Access-Client-Secret", access.clientSecret)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        try {
            http.executeCancellable(request) { response ->
                if (response.code != 200) return@executeCancellable null
                val value = JSONObject(response.body?.string() ?: "").optLong("expiresAt", 0L)
                value.takeIf { it > 0L }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
