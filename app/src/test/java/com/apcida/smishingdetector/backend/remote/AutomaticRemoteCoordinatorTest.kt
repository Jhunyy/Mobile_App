package com.apcida.smishingdetector.backend.remote

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.PilotCredentialSource
import com.apcida.smishingdetector.backend.network.PilotResult
import com.apcida.smishingdetector.backend.network.PilotSmsClient
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.network.RemoteVerdictPolicy
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class AutomaticRemoteCoordinatorTest {
    private val enabled = AiProtectionConsentStore.Snapshot(true, 1000, 2)
    private val disabled = AiProtectionConsentStore.Snapshot(false, 0, 3)
    private fun candidate(historical: Boolean = false) = RemoteSmsCandidate(
        42, "ManualTest", "Synthetic test: send your password to claim a prize.",
        2000, "stable-request-42", 2, historical
    )
    private fun requirements(consent: AiProtectionConsentStore.Snapshot = enabled,
                             endpoint: String = "https://pilot.example.org") =
        RemoteTransportGate.Requirements(consent, endpoint, "pilot.example.org",
            true, true, true, true)
    private val reviewedTestGate = RemoteAuthorizationGate {
        RemoteTransportGate.requirementsSatisfied(it)
    }

    private class FakeState(localVerdict: String = "SAFE") : RemoteAnalysisStateStore {
        private var claimed = false
        var localAssessment = RemoteVerdictPolicy.localFallback(localVerdict)
        var completed = 0
        var unavailable = 0
        var disabled = 0
        var onClaim: () -> Unit = {}
        override suspend fun claim(messageId: Long, generation: Long): Boolean {
            if (claimed) return false
            claimed = true
            onClaim()
            return true
        }
        override suspend fun complete(messageId: Long, generation: Long,
            verified: RemoteAnalysisReply.Verified): Boolean {
            if (!claimed || completed > 0 || unavailable > 0 || disabled > 0) return false
            completed++
            localAssessment = verified.classification
            return true
        }
        override suspend fun unavailable(messageId: Long, generation: Long): Boolean {
            unavailable++
            return true
        }
        override suspend fun consentDisabled(messageId: Long, generation: Long): Boolean {
            disabled++
            return true
        }
    }

    @Test fun consentHistoryAndClosedGatePreventTransmission() = runBlocking {
        var consent = disabled
        var sent = 0
        val transport = RemoteAnalysisTransport {
            sent++
            RemoteAnalysisReply.Verified("SAFE", "HIGH", "Synthetic ordinary message")
        }
        val off = AutomaticRemoteCoordinator({ consent }, FakeState(), transport, reviewedTestGate)
        assertEquals(AutomaticRemoteCoordinator.Outcome.INELIGIBLE, off.analyze(candidate(), requirements()))
        consent = enabled
        assertEquals(AutomaticRemoteCoordinator.Outcome.INELIGIBLE,
            off.analyze(candidate(historical = true), requirements()))
        assertEquals(0, sent)

        val store = FakeState()
        val closed = AutomaticRemoteCoordinator({ consent }, store, transport)
        assertEquals(AutomaticRemoteCoordinator.Outcome.GATE_CLOSED,
            closed.analyze(candidate(), requirements()))
        assertEquals("UNVERIFIED", store.localAssessment)
        assertEquals(1, store.unavailable)
        assertEquals(0, sent)

        val insecure = AutomaticRemoteCoordinator({ consent }, FakeState(), transport, reviewedTestGate)
        assertEquals(AutomaticRemoteCoordinator.Outcome.GATE_CLOSED,
            insecure.analyze(candidate(), requirements(endpoint = "http://pilot.example.org")))
        assertEquals(0, sent)
    }

    @Test fun successfulVerdictsCommitOnceAndCannotDuplicateNotifications() = runBlocking {
        for (classification in listOf("SCAM", "SAFE", "SUSPICIOUS")) {
            val store = FakeState()
            var calls = 0
            val coordinator = AutomaticRemoteCoordinator({ enabled }, store, RemoteAnalysisTransport {
                calls++
                RemoteAnalysisReply.Verified(classification, "HIGH", "Synthetic rationale")
            }, reviewedTestGate)
            assertEquals("UNVERIFIED", store.localAssessment)
            assertEquals(AutomaticRemoteCoordinator.Outcome.VERIFIED,
                coordinator.analyze(candidate(), requirements()))
            assertEquals(classification, store.localAssessment)
            assertEquals(AutomaticRemoteCoordinator.Outcome.ALREADY_HANDLED,
                coordinator.analyze(candidate(), requirements()))
            assertEquals(1, calls)
            assertEquals(1, store.completed)
        }
    }

    @Test fun revocationBeforeOrDuringRequestStopsCompletion() = runBlocking {
        var currentConsent = enabled
        var sent = 0
        val beforeStore = FakeState().apply { onClaim = { currentConsent = this@AutomaticRemoteCoordinatorTest.disabled } }
        val before = AutomaticRemoteCoordinator({ currentConsent }, beforeStore, RemoteAnalysisTransport {
            sent++
            RemoteAnalysisReply.Verified("SAFE", "HIGH", "Synthetic rationale")
        }, reviewedTestGate)
        assertEquals(AutomaticRemoteCoordinator.Outcome.CONSENT_REVOKED,
            before.analyze(candidate(), requirements()))
        assertEquals(0, sent)
        assertEquals(1, beforeStore.disabled)

        currentConsent = enabled
        val duringStore = FakeState()
        val during = AutomaticRemoteCoordinator({ currentConsent }, duringStore, RemoteAnalysisTransport {
            sent++
            currentConsent = disabled
            RemoteAnalysisReply.Verified("SAFE", "HIGH", "Synthetic rationale")
        }, reviewedTestGate)
        assertEquals(AutomaticRemoteCoordinator.Outcome.CONSENT_REVOKED,
            during.analyze(candidate(), requirements()))
        assertEquals(0, duringStore.completed)
        assertEquals("UNVERIFIED", duringStore.localAssessment)
    }

    @Test fun failuresKeepKeywordFallbackAndNeverInventSafe() = runBlocking {
        for (reason in RemoteAnalysisReply.Failure.entries) {
            val store = FakeState("SCAM")
            val coordinator = AutomaticRemoteCoordinator({ enabled }, store, RemoteAnalysisTransport {
                RemoteAnalysisReply.Failed(reason)
            }, reviewedTestGate)
            val result = coordinator.analyze(candidate(), requirements())
            assertEquals(if (reason == RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)
                AutomaticRemoteCoordinator.Outcome.CREDENTIAL_REVOKED
                else AutomaticRemoteCoordinator.Outcome.UNAVAILABLE, result)
            assertEquals("SCAM", store.localAssessment)
            assertEquals(1, store.unavailable)
        }
        val zeroRuleStore = FakeState("SAFE")
        val timedOut = AutomaticRemoteCoordinator({ enabled }, zeroRuleStore, RemoteAnalysisTransport {
            RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.TIMEOUT)
        }, reviewedTestGate)
        assertEquals(AutomaticRemoteCoordinator.Outcome.UNAVAILABLE,
            timedOut.analyze(candidate(), requirements()))
        assertEquals("UNVERIFIED", zeroRuleStore.localAssessment)

        val thrownStore = FakeState()
        val thrown = AutomaticRemoteCoordinator({ enabled }, thrownStore, RemoteAnalysisTransport {
            throw java.io.IOException("offline")
        }, reviewedTestGate)
        assertEquals(AutomaticRemoteCoordinator.Outcome.UNAVAILABLE,
            thrown.analyze(candidate(), requirements()))
        assertEquals("UNVERIFIED", thrownStore.localAssessment)
        assertFalse(candidate().toString().contains("password"))
        assertFalse(RemoteAnalysisReply.Verified("SCAM", "HIGH", "secret text")
            .toString().contains("secret text"))
    }

    @Test fun syntheticFixtureReusesAuthenticatedClientAgainstMockHttpServer() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = JsonParser().parse(request.body.clone().readUtf8()).getAsJsonObject()
                val id = json.get("messageId").getAsString()
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("""{"messageId":"$id","classification":"SCAM","confidence":"HIGH","rationale":"Synthetic warning"}""")
            }
        }
        server.start()
        try {
            val credential = object : PilotCredentialSource {
                override fun get(): String = "a".repeat(43)
                override fun clear() {}
            }
            // Test-only rewrite to a local mock server; production client still requires HTTPS.
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val local = chain.request().url.newBuilder().scheme("http")
                    .host("127.0.0.1").port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            }.build()
            val pilot = PilotSmsClient(credential, http)
            val store = FakeState()
            val coordinator = AutomaticRemoteCoordinator({ enabled }, store, RemoteAnalysisTransport { item ->
                withContext(Dispatchers.IO) {
                    when (val result = pilot.analyzeSynthetic("https://pilot.example.org",
                        "pilot.example.org", item.text)) {
                        is PilotResult.Success -> RemoteAnalysisReply.Verified(
                            result.classification, result.confidence, result.rationale)
                        is PilotResult.Failure -> RemoteAnalysisReply.Failed(
                            RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE)
                    }
                }
            }, reviewedTestGate)
            assertEquals(AutomaticRemoteCoordinator.Outcome.VERIFIED,
                coordinator.analyze(candidate(), requirements()))
            assertEquals("SCAM", store.localAssessment)
            assertEquals(1, server.requestCount)
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/sms/analyze", recorded.path)
            assertEquals("Bearer ${"a".repeat(43)}", recorded.getHeader("Authorization"))
            assertEquals("true", recorded.getHeader("x-apcida-pilot-synthetic"))
            val json = JsonParser().parse(recorded.body.readUtf8()).getAsJsonObject()
            assertEquals("ManualTest", json.get("sender").getAsString())
            assertTrue(json.get("messageId").getAsString().startsWith("pilot-"))
            OffsetDateTime.parse(json.get("receivedAt").getAsString())
            Unit
        } finally {
            server.shutdown()
        }
    }
}
