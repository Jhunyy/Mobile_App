package com.apcida.smishingdetector.backend.remote

import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.consent.AutomaticAnalysisPolicy
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.network.RemoteVerdictPolicy
import kotlinx.coroutines.CancellationException

/** A new, already screened SMS. Construction does not enqueue or transmit it. */
class RemoteSmsCandidate(
    val messageId: Long,
    val sender: String,
    val text: String,
    val receivedAt: Long,
    val requestId: String,
    val consentGeneration: Long,
    val historicalImport: Boolean,
) {
    init {
        require(messageId > 0 && text.isNotBlank() && requestId.isNotBlank())
    }

    // Avoid accidentally writing message text to a diagnostic log.
    override fun toString(): String = "RemoteSmsCandidate(messageId=$messageId)"
}

sealed interface RemoteAnalysisReply {
    data class Verified(val classification: String, val confidence: String,
                        val rationale: String) : RemoteAnalysisReply {
        override fun toString(): String = "Verified(classification=$classification)"
    }
    data class Failed(val reason: Failure) : RemoteAnalysisReply
    enum class Failure { TIMEOUT, SERVER_UNAVAILABLE, CREDENTIAL_REVOKED, INVALID_RESPONSE }
}

/** Future production transport must use a separately approved endpoint, never the synthetic pilot API. */
fun interface RemoteAnalysisTransport {
    suspend fun analyze(candidate: RemoteSmsCandidate): RemoteAnalysisReply
}

interface RemoteAnalysisStateStore {
    suspend fun claim(messageId: Long, generation: Long): Boolean
    suspend fun complete(messageId: Long, generation: Long,
                         verified: RemoteAnalysisReply.Verified): Boolean
    suspend fun unavailable(messageId: Long, generation: Long): Boolean
    suspend fun consentDisabled(messageId: Long, generation: Long): Boolean
}

fun interface RemoteAuthorizationGate {
    fun allows(requirements: RemoteTransportGate.Requirements): Boolean
}

/** The default gate is permanently closed until a separately reviewed rollout changes it. */
object ProductionRemoteAuthorizationGate : RemoteAuthorizationGate {
    override fun allows(requirements: RemoteTransportGate.Requirements): Boolean =
        RemoteTransportGate.canTransmit(requirements)
}

/** Prepared orchestration only. No production worker or receiver constructs this class. */
class AutomaticRemoteCoordinator(
    private val consentSnapshot: () -> AiProtectionConsentStore.Snapshot,
    private val state: RemoteAnalysisStateStore,
    private val transport: RemoteAnalysisTransport,
    private val gate: RemoteAuthorizationGate = ProductionRemoteAuthorizationGate,
) {
    enum class Outcome { INELIGIBLE, GATE_CLOSED, ALREADY_HANDLED, CONSENT_REVOKED,
                         VERIFIED, UNAVAILABLE, CREDENTIAL_REVOKED }

    private fun consentAllows(candidate: RemoteSmsCandidate,
                              snapshot: AiProtectionConsentStore.Snapshot): Boolean =
        candidate.consentGeneration == snapshot.generation &&
            AutomaticAnalysisPolicy.eligible(snapshot, candidate.receivedAt,
                candidate.historicalImport, true)

    suspend fun analyze(candidate: RemoteSmsCandidate,
                        requirements: RemoteTransportGate.Requirements): Outcome {
        val initial = consentSnapshot()
        if (!consentAllows(candidate, initial)) return Outcome.INELIGIBLE
        if (!state.claim(candidate.messageId, candidate.consentGeneration)) return Outcome.ALREADY_HANDLED

        // A queued item may have been revoked while waiting to claim its Room row.
        val beforeSend = consentSnapshot()
        if (!consentAllows(candidate, beforeSend)) {
            state.consentDisabled(candidate.messageId, candidate.consentGeneration)
            return Outcome.CONSENT_REVOKED
        }
        if (!gate.allows(requirements.copy(consent = beforeSend))) {
            state.unavailable(candidate.messageId, candidate.consentGeneration)
            return Outcome.GATE_CLOSED
        }

        val reply = try {
            transport.analyze(candidate)
        } catch (cancelled: CancellationException) {
            if (consentAllows(candidate, consentSnapshot()))
                state.unavailable(candidate.messageId, candidate.consentGeneration)
            else state.consentDisabled(candidate.messageId, candidate.consentGeneration)
            throw cancelled
        } catch (_: Exception) {
            RemoteAnalysisReply.Failed(RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE)
        }

        if (!consentAllows(candidate, consentSnapshot())) {
            state.consentDisabled(candidate.messageId, candidate.consentGeneration)
            return Outcome.CONSENT_REVOKED
        }
        if (reply is RemoteAnalysisReply.Verified &&
            RemoteVerdictPolicy.stateFor(reply.classification) != null &&
            reply.confidence in setOf("HIGH", "MEDIUM", "LOW") &&
            reply.rationale.isNotBlank() && reply.rationale.length <= 300) {
            return if (state.complete(candidate.messageId, candidate.consentGeneration, reply))
                Outcome.VERIFIED else Outcome.ALREADY_HANDLED
        }
        state.unavailable(candidate.messageId, candidate.consentGeneration)
        return if (reply is RemoteAnalysisReply.Failed &&
            reply.reason == RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)
            Outcome.CREDENTIAL_REVOKED else Outcome.UNAVAILABLE
    }
}
