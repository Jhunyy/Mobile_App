package com.apcida.smishingdetector.backend.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

class PilotSmsClientTest {
    private class MemoryCredential(var value: String? = "a".repeat(43)) : PilotCredentialSource {
        var clears = 0
        override fun get(): String? = value
        override fun clear() { value = null; clears++ }
    }

    private fun client(store: MemoryCredential, handler: Interceptor): PilotSmsClient =
        PilotSmsClient(store, OkHttpClient.Builder().addInterceptor(handler).build())

    private fun response(chain: Interceptor.Chain, code: Int, content: String = "{}"): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(content.toResponseBody()).build()

    @Test fun rejectsHttpAndWrongHostWithoutSending() {
        val store = MemoryCredential()
        var requests = 0
        val client = client(store, Interceptor { chain -> requests++; response(chain, 200) })
        assertTrue(client.analyzeSynthetic("http://pilot.tailnet.ts.net", "pilot.tailnet.ts.net", "hello") is PilotResult.Failure)
        assertTrue(client.analyzeSynthetic("https://other.tailnet.ts.net", "pilot.tailnet.ts.net", "hello") is PilotResult.Failure)
        assertEquals(0, requests)
    }

    @Test fun authenticatedSyntheticRequestParsesAllClassifications() {
        for (classification in listOf("SCAM", "SAFE", "SUSPICIOUS")) {
            val store = MemoryCredential()
            val client = client(store, Interceptor { chain ->
                val request = chain.request()
                assertEquals("Bearer ${store.value}", request.header("Authorization"))
                assertEquals("true", request.header("x-apcida-pilot-synthetic"))
                assertEquals("https", request.url.scheme)
                val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("ManualTest"))
                assertTrue(body.contains("pilot-"))
                val id = com.google.gson.JsonParser().parse(body).getAsJsonObject().get("messageId").getAsString()
                response(chain, 200, """{"messageId":"$id","classification":"$classification","confidence":"HIGH","rationale":"Synthetic test"}""")
            })
            val result = client.analyzeSynthetic("https://pilot.tailnet.ts.net", "pilot.tailnet.ts.net", "Synthetic test")
            assertEquals(classification, (result as PilotResult.Success).classification)
        }
    }

    @Test fun revokedCredentialIsClearedAndNetworkFailureIsReported() {
        val store = MemoryCredential()
        val unauthorized = client(store, Interceptor { chain -> response(chain, 401) })
        assertTrue(unauthorized.analyzeSynthetic("https://pilot.tailnet.ts.net", "pilot.tailnet.ts.net", "test") is PilotResult.Failure)
        assertEquals(1, store.clears)
        assertNull(store.get())
        val failed = client(MemoryCredential(), Interceptor { throw IOException("offline") })
        assertTrue(failed.analyzeSynthetic("https://pilot.tailnet.ts.net", "pilot.tailnet.ts.net", "test") is PilotResult.Failure)
    }

    @Test fun malformedResponseAndMissingCredentialFailClosed() {
        val bad = client(MemoryCredential(), Interceptor { chain -> response(chain, 200, "{}") })
        assertTrue(bad.analyzeSynthetic("https://pilot.tailnet.ts.net", "pilot.tailnet.ts.net", "test") is PilotResult.Failure)
        val missing = client(MemoryCredential(null), Interceptor { chain -> response(chain, 200) })
        assertTrue(missing.analyzeSynthetic("https://pilot.tailnet.ts.net", "pilot.tailnet.ts.net", "test") is PilotResult.Failure)
    }

    @Test fun approvedCloudflareHostUsesFullAuthenticatedPilotContract() {
        val host = "kick-olympics-sand-hostels.trycloudflare.com"
        val store = MemoryCredential()
        var calls = 0
        val pilot = client(store, Interceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals(host, request.url.host)
            assertEquals("/api/sms/analyze", request.url.encodedPath)
            assertEquals("Bearer ${store.value}", request.header("Authorization"))
            assertEquals("true", request.header("x-apcida-pilot-synthetic"))
            val raw = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = com.google.gson.JsonParser().parse(raw).getAsJsonObject()
            val id = json.get("messageId").getAsString()
            assertTrue(Regex("pilot-[A-Za-z0-9_-]{8,80}").matches(id))
            assertEquals("ManualTest", json.get("sender").getAsString())
            assertEquals("Synthetic test only", json.get("text").getAsString())
            OffsetDateTime.parse(json.get("receivedAt").getAsString())
            response(chain, 200,
                """{"messageId":"$id","classification":"SCAM","confidence":"HIGH","rationale":"Synthetic warning"}""")
        })
        assertTrue(pilot.analyzeSynthetic("https://$host", host, "Synthetic test only") is PilotResult.Success)
        assertEquals(1, calls)
        for (bad in listOf("http://$host", "https://other.trycloudflare.com", "https://$host.evil.test")) {
            assertTrue(pilot.analyzeSynthetic(bad, host, "Synthetic test only") is PilotResult.Failure)
        }
        assertEquals(1, calls)
    }
}
