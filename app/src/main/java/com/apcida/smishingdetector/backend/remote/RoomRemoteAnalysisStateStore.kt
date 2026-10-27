package com.apcida.smishingdetector.backend.remote

import android.content.Context
import com.apcida.smishingdetector.controller.SmsController

/** Room-backed atomic claim and completion, prepared but not connected to the SMS worker. */
class RoomRemoteAnalysisStateStore(context: Context) : RemoteAnalysisStateStore {
    private val controller = SmsController(context.applicationContext)

    override suspend fun claim(messageId: Long, generation: Long): Boolean =
        controller.claimRemoteAnalysis(messageId, generation)

    override suspend fun complete(messageId: Long, generation: Long,
        verified: RemoteAnalysisReply.Verified): Boolean =
        controller.applyRemoteResult(messageId, generation, verified.classification,
            verified.confidence, verified.rationale)

    override suspend fun unavailable(messageId: Long, generation: Long): Boolean =
        controller.markClaimedAiUnavailable(messageId, generation)

    override suspend fun consentDisabled(messageId: Long, generation: Long): Boolean =
        controller.markClaimedConsentDisabled(messageId, generation)
}
