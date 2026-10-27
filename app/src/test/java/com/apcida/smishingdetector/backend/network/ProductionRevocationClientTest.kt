package com.apcida.smishingdetector.backend.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProductionRevocationClientTest {
    private val token = "a".repeat(43)
    private fun client(handler: Interceptor) = ProductionRevocationClient(
        OkHttpClient.Builder().addInterceptor(handler).followRedirects(false)
            .followSslRedirects(false).build())
    private fun response(chain: Interceptor.Chain, code: Int) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("synthetic").body("".toResponseBody()).build()

    @Test fun authenticatedDeletionSendsNoSmsFieldsOrBody() = runBlocking {
        var sent = 0
        val result = client(Interceptor { chain ->
            sent++
            val request = chain.request()
            assertEquals("DELETE", request.method)
            assertEquals("https", request.url.scheme)
            assertEquals("/api/v1/installations/me", request.url.encodedPath)
            assertEquals("Bearer $token", request.header("Authorization"))
            assertNull(request.header("Idempotency-Key"))
            val body = Buffer().also { request.body?.writeTo(it) }
            assertEquals(0L, body.size)
            response(chain, 204)
        }).revoke("https://sms.example.org", "sms.example.org", token)
        assertEquals(ProductionRevocationClient.Result.CONFIRMED, result)
        assertEquals(1, sent)
    }

    @Test fun insecureOrUnapprovedOriginsNeverSend() = runBlocking {
        var sent = 0
        val item = client(Interceptor { chain -> sent++; response(chain, 204) })
        for (origin in listOf("http://sms.example.org", "https://other.example.org",
            "https://temp.trycloudflare.com")) {
            assertEquals(ProductionRevocationClient.Result.CONFIGURATION_REQUIRED,
                item.revoke(origin, "sms.example.org", token))
        }
        assertEquals(0, sent)
    }

    @Test fun rejectedUnavailableAndProxyTimedOutResultsStayUnconfirmed() = runBlocking {
        for ((status, expected) in listOf(
            401 to ProductionRevocationClient.Result.AUTH_REJECTED,
            403 to ProductionRevocationClient.Result.AUTH_REJECTED,
            503 to ProductionRevocationClient.Result.RETRYABLE_FAILURE,
            524 to ProductionRevocationClient.Result.RETRYABLE_FAILURE
        )) {
            val result = client(Interceptor { chain -> response(chain, status) })
                .revoke("https://sms.example.org", "sms.example.org", token)
            assertEquals(expected, result)
        }
        assertEquals(ProductionRevocationClient.Result.RETRYABLE_FAILURE,
            client(Interceptor { throw IOException("synthetic offline") })
                .revoke("https://sms.example.org", "sms.example.org", token))
    }

    @Test fun retriesAreBoundedAndTimingFitsProxyBudget() {
        assertTrue(ProductionRevocationRetryPolicy.shouldRetry(0))
        assertTrue(ProductionRevocationRetryPolicy.shouldRetry(1))
        assertFalse(ProductionRevocationRetryPolicy.shouldRetry(2))
        assertTrue(ProductionTiming.READ_SECONDS < ProductionTiming.CALL_SECONDS)
        assertTrue(ProductionTiming.CALL_SECONDS < 125)
        assertTrue(ProductionTiming.ORPHAN_STALE_MS > ProductionTiming.CALL_SECONDS * 1000)
    }
}
