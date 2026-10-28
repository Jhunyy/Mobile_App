package com.apcida.smishingdetector.backend.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProductionHttpCallTest {
    @Test fun coroutineCancellationStopsAWaitingHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(server.url("/synthetic-only")).build()
            val work = launch(Dispatchers.IO) {
                client.executeCancellable(request) { it.code }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) { work.cancelAndJoin() }
        } finally {
            server.shutdown()
        }
    }
}
