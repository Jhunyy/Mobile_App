package com.apcida.smishingdetector.manual

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ManualAiClientTest {
    private val id = "manual-123"
    private val timestamp = "2026-10-27T12:00:00+08:00"
    private val url = "http://192.168.1.23:3000"

    private fun client(status: Int = 200, body: String = "{}", error: Exception? = null,
                       inspect: (okhttp3.Request) -> Unit = {}): ManualAiClient {
        val interceptor = Interceptor { chain ->
            inspect(chain.request())
            if (error != null) throw error
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        return ManualAiClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }

    private fun analyze(client: ManualAiClient, key: String = "temporary-development-key") =
        client.analyze(url, key, "Synthetic test SMS", id, timestamp)

    @Test fun authenticatedRequestIsOnlySentWhenAnalyzeIsCalled() {
        var calls = 0
        val client = client(body = success("SAFE")) { request ->
            calls++
            assertEquals("POST", request.method)
            assertEquals("/api/sms/analyze", request.url.encodedPath)
            assertEquals("temporary-development-key", request.header("x-api-key"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val payload = buffer.readUtf8()
            assertTrue(payload.contains("Synthetic test SMS"))
            assertTrue(payload.contains("ManualTest"))
            assertTrue(payload.contains(id))
        }
        assertEquals(0, calls)
        assertTrue(analyze(client) is ManualAiResult.Success)
        assertEquals(1, calls)
    }

    @Test fun allThreeVerdictsAreParsed() {
        for (classification in RemoteClassification.entries) {
            val result = analyze(client(body = success(classification.name)))
            assertEquals(ManualAiResult.Success(classification, RemoteConfidence.MEDIUM, "Test rationale."), result)
        }
    }

    @Test fun invalidKeyAndServerFailuresHaveClearErrors() {
        assertTrue((analyze(client(status = 401)) as ManualAiResult.Failure).message.contains("Invalid API key"))
        assertTrue((analyze(client(error = ConnectException("offline"))) as ManualAiResult.Failure)
            .message.contains("unavailable"))
        assertTrue((analyze(client(error = SocketTimeoutException("timeout"))) as ManualAiResult.Failure)
            .message.contains("timed out"))
        assertTrue((analyze(client(status = 504)) as ManualAiResult.Failure).message.contains("timed out"))
    }

    @Test fun malformedResponsesAndPublicAddressesAreRejected() {
        for (body in listOf("not JSON", "{}", success("UNKNOWN"), success("SAFE", "other-id"))) {
            assertTrue((analyze(client(body = body)) as ManualAiResult.Failure).message.contains("malformed"))
        }
        var called = false
        val client = client(body = success("SAFE")) { called = true }
        assertTrue(client.analyze("http://8.8.8.8:3000", "key", "Synthetic", id, timestamp) is ManualAiResult.Failure)
        assertTrue(client.analyze("http://192.168.1.23:3000/path", "key", "Synthetic", id, timestamp) is ManualAiResult.Failure)
        assertFalse(called)
    }

    private fun success(classification: String, messageId: String = id) =
        """{"messageId":"$messageId","classification":"$classification","confidence":"MEDIUM","rationale":"Test rationale."}"""
}
