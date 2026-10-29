package com.apcida.smishingdetector.backend.remote

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredential
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialSource
import com.apcida.smishingdetector.backend.network.ProductionCredentialSource
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.google.gson.JsonParser
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
import java.net.SocketTimeoutException

class ProductionSmsTransportTest {
    private val origin = "https://sms.example.org"
    private val id = "6d5f9250-3a47-42a8-a06b-68f0b8a82691"
    private val accessCredential = CloudflareAccessCredential("a".repeat(32) + ".access",
        "cfast_" + "b".repeat(48))
    private val accessSource = CloudflareAccessCredentialSource { accessCredential }
    private val consent = AiProtectionConsentStore.Snapshot(true, 1000, 2)
    private fun requirements(endpoint: String = origin) = RemoteTransportGate.Requirements(
        consent, endpoint, "sms.example.org", true, true, true, true)
    private val reviewedTestGate = RemoteAuthorizationGate {
        RemoteTransportGate.requirementsSatisfied(it)
    }
    private fun candidate() = RemoteSmsCandidate(42, "ManualTest",
        "Synthetic test: do not share your code", 2000, id, 2, false)
    private class MemoryCredential(var token: String? = "a".repeat(43)) : ProductionCredentialSource {
        var quarantines = 0
        var quarantined = false
        override fun get(): String? = if (quarantined) null else token
        override fun quarantine() { quarantined = true; quarantines++ }
        override fun clear() { token = null }
    }
    private fun response(chain: Interceptor.Chain, code: Int, body: String = "{}") =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("synthetic").body(body.toResponseBody()).build()
    private fun transport(credential: MemoryCredential, handler: Interceptor,
        gate: RemoteAuthorizationGate = reviewedTestGate, host: String = "sms.example.org",
        endpoint: String = origin,
        access: CloudflareAccessCredentialSource = accessSource) = ProductionSmsTransport(endpoint, host,
        credential, access,
        { requirements(endpoint).copy(approvedHost = host) }, gate, OkHttpClient.Builder().addInterceptor(handler)
            .followRedirects(false).followSslRedirects(false).build())

    @Test fun defaultProductionGateAndInvalidOriginsSendNothing() = runBlocking {
        var sent = 0
        val handler = Interceptor { chain -> sent++; response(chain, 200) }
        val closed = transport(MemoryCredential(), handler, ProductionRemoteAuthorizationGate)
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE),
            closed.analyze(candidate()))
        for (endpoint in listOf("http://sms.example.org", "https://other.example.org",
                "https://sms.example.org.evil.test")) {
            val item = transport(MemoryCredential(), handler, endpoint = endpoint)
            assertTrue(item.analyze(candidate()) is RemoteAnalysisReply.Failed)
        }
        assertTrue(transport(MemoryCredential(), handler, endpoint = "https://temp.trycloudflare.com",
            host = "temp.trycloudflare.com").analyze(candidate()) is RemoteAnalysisReply.Failed)
        val historical = RemoteSmsCandidate(43, "ManualTest", "Synthetic history", 2000, id, 2, true)
        assertTrue(transport(MemoryCredential(), handler).analyze(historical) is RemoteAnalysisReply.Failed)
        assertEquals(0, sent)
    }

    @Test fun accessCredentialsRejectHeaderInjectionAndDoNotPrintSecrets() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareAccessCredential("bad\nheader.access", "cfast_" + "b".repeat(48))
        }
        assertFalse(accessCredential.toString().contains(accessCredential.clientSecret))
    }

    @Test fun sendsAuthenticatedProductionContractWithoutPilotMarker() = runBlocking {
        val credential = MemoryCredential()
        var sent = 0
        val item = transport(credential, Interceptor { chain ->
            sent++
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals("/api/v1/sms/analyze", request.url.encodedPath)
            assertEquals("Bearer ${credential.token}", request.header("Authorization"))
            assertEquals(accessCredential.clientId, request.header("CF-Access-Client-Id"))
            assertEquals(accessCredential.clientSecret, request.header("CF-Access-Client-Secret"))
            assertEquals(id, request.header("Idempotency-Key"))
            assertNull(request.header("x-apcida-pilot-synthetic"))
            val raw = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = JsonParser().parse(raw).asJsonObject
            assertEquals(id, json.get("messageId").asString)
            assertEquals("ManualTest", json.get("sender").asString)
            assertEquals("Synthetic test: do not share your code", json.get("text").asString)
            assertEquals("1970-01-01T00:00:02Z", json.get("receivedAt").asString)
            response(chain, 200, """{"messageId":"$id","classification":"SCAM","confidence":"HIGH","rationale":"Mock warning"}""")
        })
        val result = item.analyze(candidate()) as RemoteAnalysisReply.Verified
        assertEquals("SCAM", result.classification)
        assertEquals(1, sent)
        assertFalse(candidate().toString().contains("do not share"))
    }

    @Test fun rejectionTimeoutOfflineAndBadResponseFailClosed() = runBlocking {
        val rejected = MemoryCredential()
        var rejectionRequests = 0
        val rejectedTransport = transport(rejected, Interceptor { chain ->
            rejectionRequests++
            response(chain, 401)
        })
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
            rejectedTransport.analyze(candidate()))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
            rejectedTransport.analyze(candidate()))
        assertEquals(1, rejectionRequests)
        assertEquals(1, rejected.quarantines)
        assertNull(rejected.get())
        assertNotNull(rejected.token)
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
            transport(MemoryCredential(), Interceptor { error("Should not send without Access") },
                access = CloudflareAccessCredentialSource { null }).analyze(candidate()))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
            transport(MemoryCredential(null), Interceptor { error("Should not send") }).analyze(candidate()))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.TIMEOUT),
            transport(MemoryCredential(), Interceptor { throw SocketTimeoutException("synthetic timeout") }).analyze(candidate()))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE),
            transport(MemoryCredential(), Interceptor { throw IOException("synthetic offline") }).analyze(candidate()))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE),
            transport(MemoryCredential(), Interceptor { chain -> response(chain, 524) }).analyze(candidate()))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.INVALID_RESPONSE),
            transport(MemoryCredential(), Interceptor { chain -> response(chain, 200,
                """{"messageId":"wrong","classification":"SAFE","confidence":"HIGH","rationale":"Mock"}""") })
                .analyze(candidate()))
    }
}
