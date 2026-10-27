package com.apcida.smishingdetector.backend.remote

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.model.entity.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses only an in-memory Room database and synthetic text; never starts a network request. */
@RunWith(AndroidJUnit4::class)
class RemoteAnalysisDaoInstrumentedTest {
    @Test fun claimAndCompletionAreSingleUseAndHistoryCannotBeClaimed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.messageDao()
            val newId = dao.insertMessage(Message(
                content = "Synthetic new SMS", receivedAt = 2000,
                deterministicRiskLevel = "SAFE", finalClassification = "UNVERIFIED",
                processingState = "AI_QUEUED", detectionState = "REMOTE_PENDING",
                verdictSource = "LOCAL_RULES", consentGeneration = 2
            ))
            assertEquals(1, dao.claimRemoteAnalysis(newId, 2))
            assertEquals(0, dao.claimRemoteAnalysis(newId, 2))
            assertEquals(1, dao.completeRemoteAnalysis(newId, 2,
                "SCAM", "HIGH", "Synthetic rationale", "REMOTE_SCAM"))
            assertEquals(0, dao.completeRemoteAnalysis(newId, 2,
                "SAFE", "HIGH", "Second response", "REMOTE_SAFE"))
            val result = dao.getMessageById(newId)!!
            assertEquals("SCAM", result.finalClassification)
            assertEquals("REMOTE_GEMMA_RAG", result.verdictSource)
            assertEquals("COMPLETED", result.processingState)

            val historicalId = dao.insertMessage(Message(
                content = "Synthetic historical SMS", receivedAt = 500,
                processingState = "REMOTE_CONSENT_DISABLED",
                detectionState = "REMOTE_CONSENT_DISABLED", consentGeneration = 0
            ))
            assertEquals(0, dao.claimRemoteAnalysis(historicalId, 2))
        } finally {
            database.close()
        }
    }

    @Test fun failedZeroRuleAnalysisRemainsUnverified() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.messageDao()
            val id = dao.insertMessage(Message(
                content = "Synthetic ordinary SMS", receivedAt = 2000,
                deterministicRiskLevel = "SAFE", finalClassification = "UNVERIFIED",
                processingState = "AI_QUEUED", detectionState = "REMOTE_PENDING",
                verdictSource = "LOCAL_RULES", consentGeneration = 2
            ))
            assertEquals(1, dao.claimRemoteAnalysis(id, 2))
            assertEquals(1, dao.failClaimedRemoteAnalysis(id, 2))
            assertEquals("UNVERIFIED", dao.getMessageById(id)!!.finalClassification)
            assertEquals("REMOTE_UNAVAILABLE", dao.getMessageById(id)!!.detectionState)
        } finally {
            database.close()
        }
    }
    @Test fun concurrentClaimsPermitExactlyOneRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.messageDao()
            val id = dao.insertMessage(Message(content = "Synthetic parallel SMS", receivedAt = 2000,
                finalClassification = "UNVERIFIED", processingState = "AI_QUEUED",
                detectionState = "REMOTE_PENDING", verdictSource = "LOCAL_RULES", consentGeneration = 2))
            val claims = (1..12).map { async(Dispatchers.IO) { dao.claimRemoteAnalysis(id, 2) } }.awaitAll()
            assertEquals(1, claims.sum())
            assertEquals("AI_ANALYZING", dao.getMessageById(id)!!.processingState)
            assertEquals(1, dao.completeRemoteAnalysis(id, 2, "SUSPICIOUS", "MEDIUM",
                "Synthetic mock response", "REMOTE_SUSPICIOUS"))
            assertEquals(0, dao.completeRemoteAnalysis(id, 2, "SAFE", "HIGH",
                "Duplicate mock response", "REMOTE_SAFE"))
            assertEquals("SUSPICIOUS", dao.getMessageById(id)!!.finalClassification)
        } finally { database.close() }
    }

    @Test fun revokedGenerationCannotCommitVerifiedResponse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.messageDao()
            val id = dao.insertMessage(Message(content = "Synthetic revoked SMS", receivedAt = 2000,
                finalClassification = "UNVERIFIED", processingState = "AI_QUEUED",
                detectionState = "REMOTE_PENDING", verdictSource = "LOCAL_RULES", consentGeneration = 2))
            assertEquals(0, dao.claimRemoteAnalysis(id, 3))
            assertEquals(1, dao.claimRemoteAnalysis(id, 2))
            dao.markPendingRemoteConsentDisabled(3)
            assertEquals(0, dao.completeRemoteAnalysis(id, 2, "SAFE", "HIGH",
                "Late mock response", "REMOTE_SAFE"))
            val result = dao.getMessageById(id)!!
            assertEquals("UNVERIFIED", result.finalClassification)
            assertEquals("REMOTE_CONSENT_DISABLED", result.detectionState)
            assertEquals("LOCAL_RULES", result.verdictSource)
        } finally { database.close() }
    }

    @Test fun networkFailurePreservesKeywordWarning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.messageDao()
            val id = dao.insertMessage(Message(content = "Synthetic urgent payment demand", receivedAt = 2000,
                deterministicRiskLevel = "SCAM", finalClassification = "SCAM",
                processingState = "AI_QUEUED", detectionState = "REMOTE_PENDING",
                verdictSource = "LOCAL_RULES", consentGeneration = 2))
            assertEquals(1, dao.claimRemoteAnalysis(id, 2))
            assertEquals(1, dao.failClaimedRemoteAnalysis(id, 2))
            val result = dao.getMessageById(id)!!
            assertEquals("SCAM", result.finalClassification)
            assertEquals("LOCAL_RULES", result.verdictSource)
            assertEquals("REMOTE_UNAVAILABLE", result.detectionState)
            assertEquals(0, dao.claimRemoteAnalysis(id, 2))
        } finally { database.close() }
    }

    @Test fun pendingAndNotificationStatePersistAfterDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase3l-reopen-${UUID.randomUUID()}.db"
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val id = try {
                first.messageDao().insertMessage(Message(content = "Synthetic restart SMS",
                    receivedAt = 2000, finalClassification = "UNVERIFIED",
                    processingState = "AI_QUEUED", detectionState = "REMOTE_PENDING",
                    verdictSource = "LOCAL_RULES", consentGeneration = 2,
                    notificationPosted = true))
            } finally { first.close() }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val row = reopened.messageDao().getMessageById(id)!!
                assertEquals("UNVERIFIED", row.finalClassification)
                assertEquals("REMOTE_PENDING", row.detectionState)
                assertTrue(row.notificationPosted)
                assertEquals(1, reopened.messageDao().claimRemoteAnalysis(id, 2))
                assertEquals(0, reopened.messageDao().claimRemoteAnalysis(id, 2))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromVersionSevenPreservesRowAndAddsLocalDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase3l-migration-${UUID.randomUUID()}.db"
        try {
            val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val id = try {
                initial.messageDao().insertMessage(Message(content = "Synthetic legacy SMS",
                    receivedAt = 1000, finalClassification = "SCAM"))
            } finally { initial.close() }
            // A private test database is shaped like v7. No installed app data is touched.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                SQLiteDatabase.OPEN_READWRITE).use { raw ->
                raw.execSQL("PRAGMA foreign_keys=OFF")
                val columns = mutableListOf<String>()
                raw.rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val field = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (field in setOf("detection_state", "verdict_source", "remote_request_id",
                                "consent_generation", "notification_posted")) continue
                        val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                        val required = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                        val fallback = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                        val key = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) == 1
                        columns += buildString {
                            append("`$field` $type")
                            if (key) append(" PRIMARY KEY AUTOINCREMENT")
                            if (required) append(" NOT NULL")
                            if (fallback != null) append(" DEFAULT $fallback")
                        }
                    }
                }
                val names = columns.map { it.substringAfter('`').substringBefore('`') }
                val fields = names.joinToString(", ") { "`$it`" }
                raw.execSQL("CREATE TABLE messages_v7 (${columns.joinToString(", ")})")
                raw.execSQL("INSERT INTO messages_v7 ($fields) SELECT $fields FROM messages")
                raw.execSQL("DROP TABLE messages")
                raw.execSQL("ALTER TABLE messages_v7 RENAME TO messages")
                raw.execSQL("CREATE INDEX index_messages_sender_hash_received_at ON messages(sender_hash, received_at)")
                raw.execSQL("CREATE UNIQUE INDEX index_messages_source_sms_id ON messages(source_sms_id)")
                raw.version = 7
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_7_8).build()
            try {
                val row = migrated.messageDao().getMessageById(id)!!
                assertEquals("Synthetic legacy SMS", row.content)
                assertEquals("SCAM", row.finalClassification)
                assertEquals("LEGACY_ON_DEVICE", row.detectionState)
                assertEquals("LEGACY_ON_DEVICE", row.verdictSource)
                assertEquals(0L, row.consentGeneration)
                assertFalse(row.notificationPosted)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }


    @Test fun mockedTimeoutAndOfflineResponsesNeverCreateSafeVerdict() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.messageDao()
            val consent = AiProtectionConsentStore.Snapshot(true, 1000, 2)
            val requirements = RemoteTransportGate.Requirements(consent,
                "https://pilot.example.org", "pilot.example.org", true, true, true, true)
            val state = object : RemoteAnalysisStateStore {
                override suspend fun claim(messageId: Long, generation: Long) =
                    dao.claimRemoteAnalysis(messageId, generation) == 1
                override suspend fun complete(messageId: Long, generation: Long,
                    verified: RemoteAnalysisReply.Verified) =
                    dao.completeRemoteAnalysis(messageId, generation, verified.classification,
                        verified.confidence, verified.rationale, "REMOTE_${verified.classification}") == 1
                override suspend fun unavailable(messageId: Long, generation: Long) =
                    dao.failClaimedRemoteAnalysis(messageId, generation) == 1
                override suspend fun consentDisabled(messageId: Long, generation: Long) =
                    dao.disableClaimedRemoteAnalysis(messageId, generation) == 1
            }
            for (failure in listOf(RemoteAnalysisReply.Failure.TIMEOUT,
                    RemoteAnalysisReply.Failure.SERVER_UNAVAILABLE,
                    RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)) {
                val id = dao.insertMessage(Message(content = "Synthetic failing SMS", receivedAt = 2000,
                    deterministicRiskLevel = "SAFE", finalClassification = "UNVERIFIED",
                    processingState = "AI_QUEUED", detectionState = "REMOTE_PENDING",
                    verdictSource = "LOCAL_RULES", consentGeneration = 2))
                var calls = 0
                val coordinator = AutomaticRemoteCoordinator({ consent }, state,
                    RemoteAnalysisTransport { calls++; RemoteAnalysisReply.Failed(failure) },
                    RemoteAuthorizationGate { RemoteTransportGate.requirementsSatisfied(it) })
                val result = coordinator.analyze(RemoteSmsCandidate(id, "ManualTest",
                    "Synthetic failing SMS", 2000, "pilot-$id", 2, false), requirements)
                assertEquals(if (failure == RemoteAnalysisReply.Failure.CREDENTIAL_REVOKED)
                    AutomaticRemoteCoordinator.Outcome.CREDENTIAL_REVOKED
                    else AutomaticRemoteCoordinator.Outcome.UNAVAILABLE, result)
                assertEquals(1, calls)
                val row = dao.getMessageById(id)!!
                assertEquals("UNVERIFIED", row.finalClassification)
                assertEquals("REMOTE_UNAVAILABLE", row.detectionState)
                assertEquals("LOCAL_RULES", row.verdictSource)
                assertEquals(0, dao.claimRemoteAnalysis(id, 2))
            }
        } finally { database.close() }
    }

}
