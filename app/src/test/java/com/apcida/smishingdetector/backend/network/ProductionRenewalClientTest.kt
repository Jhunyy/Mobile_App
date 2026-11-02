package com.apcida.smishingdetector.backend.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductionRenewalClientTest {
    private val bearer = "a".repeat(43)
    private val access = CloudflareAccessCredential("b".repeat(32) + ".access",
        "cfast_" + "c".repeat(48))

    private fun response(chain: Interceptor.Chain, code: Int, body: String) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("synthetic").body(body.toResponseBody()).build()

    @Test fun renewalSendsNoSmsAndAcceptsOnlySuccessfulExpiry() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("https", request.url.scheme)
            assertEquals("/api/v1/installations/me/renew", request.url.encodedPath)
            assertEquals("Bearer $bearer", request.header("Authorization"))
            assertEquals(access.clientId, request.header("CF-Access-Client-Id"))
            assertEquals(access.clientSecret, request.header("CF-Access-Client-Secret"))
            val body = Buffer().also { request.body?.writeTo(it) }
            assertEquals(0L, body.size)
            response(chain, 200, "{\"expiresAt\":1796083200000}")
        }).build()
        assertEquals(1_796_083_200_000L,
            ProductionRenewalClient(http).renew("sms.example.org", bearer, access))
        assertEquals(1, calls)
        assertNull(ProductionRenewalClient(http).renew("bad.trycloudflare.com", bearer, access))
        assertEquals(1, calls)
    }

    @Test fun serverErrorDoesNotExtendPhoneExpiry() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            response(chain, 503, "{\"error\":\"RENEWAL_UNAVAILABLE\"}")
        }).build()
        assertNull(ProductionRenewalClient(http).renew("sms.example.org", bearer, access))
    }
}
