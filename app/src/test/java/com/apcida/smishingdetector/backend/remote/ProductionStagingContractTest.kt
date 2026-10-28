package com.apcida.smishingdetector.backend.remote

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialSource
import com.apcida.smishingdetector.backend.network.ProductionRevocationClient
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Exercises the real production route on an isolated loopback staging listener.
 * The URL rewrite exists only in this JVM test; application HTTPS policy stays intact.
 */
class ProductionStagingContractTest {
    private val approvedOrigin = "https://staging.apcida.invalid"
    private val consent = AiProtectionConsentStore.Snapshot(true, 1000, 7)
    private val testGate = RemoteAuthorizationGate { RemoteTransportGate.requirementsSatisfied(it) }
    private lateinit var loopback: String
    private lateinit var validToken: String
    private lateinit var revokedToken: String
    private lateinit var androidRevokeToken: String

    @Before fun requireIsolatedListener() {
        loopback = System.getenv("APCIDA_STAGING_HTTP_BASE") ?: ""
        validToken = System.getenv("APCIDA_STAGING_TOKEN") ?: ""
        revokedToken = System.getenv("APCIDA_STAGING_REVOKED_TOKEN") ?: ""
        androidRevokeToken = System.getenv("APCIDA_STAGING_ANDROID_REVOKE_TOKEN") ?: ""
        assumeTrue("Phase 3O isolated staging listener is not running",
            loopback.startsWith("http://127.0.0.1:") &&
                validToken.matches(Regex("[A-Za-z0-9_-]{43}")) &&
                revokedToken.matches(Regex("[A-Za-z0-9_-]{43}")) &&
                androidRevokeToken.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    private class MemoryCredential(var token: String?) : ProductionCredentialSource {
        var quarantines = 0
        var quarantined = false
        override fun get(): String? = if (quarantined) null else token
        override fun quarantine() { quarantined = true; quarantines++ }
        override fun clear() { token = null }
    }

    private fun candidate(kind: String, id: String = UUID.randomUUID().toString(),
        historical: Boolean = false) = RemoteSmsCandidate(31, "ManualTest",
        "STAGING SYNTHETIC: $kind Android contract fixture", 2000, id, 7, historical)

    private fun transport(credentials: MemoryCredential, snapshot: () -> AiProtectionConsentStore.Snapshot = { consent },
        origin: String = approvedOrigin, local: String = loopback,
        sent: (() -> Unit)? = null): ProductionSmsTransport {
        val localUrl = local.toHttpUrl()
        val http = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(Interceptor { chain ->
                sent?.invoke()
                val replacement = chain.request().url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(localUrl.port).build()
                chain.proceed(chain.request().newBuilder().url(replacement).build())
            }).build()
        return ProductionSmsTransport(origin, "staging.apcida.invalid", credentials,
            { RemoteTransportGate.Requirements(snapshot(), origin, "staging.apcida.invalid",
                true, true, true, true) }, testGate, http)
    }

    @Test fun verdictsAndDuplicateReplayMatchProductionContract() = runBlocking {
        val client = transport(MemoryCredential(validToken))
        for (kind in listOf("SCAM", "SAFE", "SUSPICIOUS")) {
            val fixture = candidate(kind)
            val first = client.analyze(fixture)
            assertTrue("$kind was not verified: $first", first is RemoteAnalysisReply.Verified)
            val verdict = first as RemoteAnalysisReply.Verified
            assertEquals(kind, verdict.classification)
            assertEquals("HIGH", verdict.confidence)
            assertEquals("Synthetic staging response", verdict.rationale)
            assertEquals(first, client.analyze(fixture))
        }
    }

    @Test fun invalidAndRevokedInstallationCredentialsAreQuarantined() = runBlocking {
        for (token in listOf("z".repeat(43), revokedToken)) {
            val credentials = MemoryCredential(token)
            assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
                transport(credentials).analyze(candidate("SCAM")))
            assertEquals(1, credentials.quarantines)
            assertNull(credentials.get())
            assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
                transport(credentials).analyze(candidate("SCAM")))
            assertEquals(1, credentials.quarantines)
        }
    }

    @Test fun serverTimeoutAndUnavailabilityPreserveUnverifiedFallback() = runBlocking {
        val timedOut = transport(MemoryCredential(validToken)).analyze(candidate("TIMEOUT"))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.TIMEOUT), timedOut)
        assertFalse(timedOut is RemoteAnalysisReply.Verified)
        val unreachable = transport(MemoryCredential(validToken),
            local = "http://127.0.0.1:1").analyze(candidate("SAFE"))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE), unreachable)
        assertFalse(unreachable is RemoteAnalysisReply.Verified)
    }

    @Test fun deletionOnlyClientRevokesOwnBackendCacheWithoutSmsBody() = runBlocking {
        val localUrl = loopback.toHttpUrl()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor(Interceptor { chain ->
                val replacement = chain.request().url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(localUrl.port).build()
                chain.proceed(chain.request().newBuilder().url(replacement).build())
            }).build()
        val client = ProductionRevocationClient(http)
        assertEquals(ProductionRevocationClient.Result.AUTH_REJECTED,
            client.revoke(approvedOrigin, "staging.apcida.invalid", revokedToken))
        assertEquals(ProductionRevocationClient.Result.CONFIRMED,
            client.revoke(approvedOrigin, "staging.apcida.invalid", androidRevokeToken))
        assertEquals(ProductionRevocationClient.Result.AUTH_REJECTED,
            client.revoke(approvedOrigin, "staging.apcida.invalid", androidRevokeToken))
        assertEquals(RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED),
            transport(MemoryCredential(androidRevokeToken)).analyze(candidate("SCAM")))
    }

    @Test fun revokedConsentHistoricalMessagesAndHttpOriginNeverSend() = runBlocking {
        var sent = 0
        val counting: () -> Unit = { sent++; Unit }
        val revoked = transport(MemoryCredential(validToken),
            snapshot = { consent.copy(enabled = false) }, sent = counting)
        assertTrue(revoked.analyze(candidate("SCAM")) is RemoteAnalysisReply.Failed)
        assertTrue(transport(MemoryCredential(validToken), sent = counting)
            .analyze(candidate("SCAM", historical = true)) is RemoteAnalysisReply.Failed)
        assertTrue(transport(MemoryCredential(validToken), origin = "http://staging.apcida.invalid",
            sent = counting).analyze(candidate("SCAM")) is RemoteAnalysisReply.Failed)
        assertEquals(0, sent)
        assertFalse(RemoteTransportGate.PRODUCTION_READY)
    }
}
