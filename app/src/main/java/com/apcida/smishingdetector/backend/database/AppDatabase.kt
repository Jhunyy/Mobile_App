package com.apcida.smishingdetector.backend.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.apcida.smishingdetector.backend.database.dao.KeywordDao
import com.apcida.smishingdetector.backend.database.dao.MessageDao
import com.apcida.smishingdetector.backend.database.dao.MessageKeywordDao
import com.apcida.smishingdetector.backend.database.dao.ReportDao
import com.apcida.smishingdetector.backend.database.dao.SafetyTipDao
import com.apcida.smishingdetector.model.entity.Keyword
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.MessageKeyword
import com.apcida.smishingdetector.model.entity.Report
import com.apcida.smishingdetector.model.entity.SafetyTip

@Database(
    entities = [
        Message::class,
        Keyword::class,
        MessageKeyword::class,
        Report::class,
        SafetyTip::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun keywordDao(): KeywordDao
    abstract fun messageKeywordDao(): MessageKeywordDao
    abstract fun reportDao(): ReportDao
    abstract fun safetyTipDao(): SafetyTipDao

    companion object {

        // Both branches used version 2 for different additive message columns.
        // Add only missing columns so either installed schema keeps its rows.
        private fun addMissingMessageColumns(database: SupportSQLiteDatabase) {
            val columns = mutableSetOf<String>()
            database.query("PRAGMA table_info(messages)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            }

            val finalClassificationMissing = "final_classification" !in columns
            val additions = mapOf(
                "sender_address" to "TEXT",
                "deterministic_risk_level" to "TEXT NOT NULL DEFAULT 'SAFE'",
                "matched_indicators" to "TEXT NOT NULL DEFAULT ''",
                "ai_analysis_status" to "TEXT NOT NULL DEFAULT 'NOT_STARTED'",
                "processing_state" to "TEXT NOT NULL DEFAULT 'COMPLETED'",
                "final_classification" to "TEXT NOT NULL DEFAULT 'SAFE'"
            )
            additions.forEach { (column, definition) ->
                if (column !in columns) {
                    database.execSQL("ALTER TABLE messages ADD COLUMN $column $definition")
                }
            }
            if (finalClassificationMissing) {
                database.execSQL(
                    """
                    UPDATE messages
                    SET deterministic_risk_level = CASE
                            WHEN risk_score < 30 THEN 'SAFE'
                            WHEN risk_score < 60 THEN 'SUSPICIOUS'
                            ELSE 'SCAM'
                        END,
                        final_classification = risk_level
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(database: SupportSQLiteDatabase) = addMissingMessageColumns(database)
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) = addMissingMessageColumns(database)
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE reports_new (
                        report_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        message_id INTEGER,
                        client_report_id TEXT,
                        report_type TEXT NOT NULL,
                        remarks TEXT,
                        risk_score_snapshot REAL,
                        gemma_classification_snapshot TEXT,
                        gemma_rationale_snapshot TEXT,
                        gemma_confidence_snapshot TEXT,
                        final_verdict_snapshot TEXT,
                        rule_verdict_snapshot TEXT,
                        processing_state_snapshot TEXT,
                        ai_status_snapshot TEXT,
                        matched_indicators_snapshot TEXT,
                        sms_text_snapshot TEXT,
                        app_version_snapshot TEXT,
                        model_version_snapshot TEXT,
                        reported_at INTEGER NOT NULL,
                        is_sent INTEGER NOT NULL,
                        FOREIGN KEY(message_id) REFERENCES messages(message_id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO reports_new (
                        report_id, message_id, report_type, remarks, risk_score_snapshot,
                        gemma_classification_snapshot, gemma_rationale_snapshot,
                        matched_indicators_snapshot, reported_at, is_sent
                    )
                    SELECT report_id, reports.message_id, report_type, remarks, risk_score_snapshot,
                           gemma_classification_snapshot, gemma_rationale_snapshot,
                           messages.matched_indicators, reported_at, is_sent
                    FROM reports LEFT JOIN messages ON reports.message_id = messages.message_id
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE reports")
                database.execSQL("ALTER TABLE reports_new RENAME TO reports")
                database.execSQL("CREATE INDEX index_reports_message_id ON reports(message_id)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN is_saved_contact INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN has_strong_rule_signal INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN combined_risk_score INTEGER")
                database.execSQL("ALTER TABLE messages ADD COLUMN rule_risk_points INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN ai_risk_points INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN history_risk_points INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN sender_risk_points INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_sender_hash_received_at ON messages(sender_hash, received_at)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN source_sms_id INTEGER")
                database.execSQL("ALTER TABLE messages ADD COLUMN alerts_enabled INTEGER NOT NULL DEFAULT 1")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_source_sms_id ON messages(source_sms_id)"
                )
            }
        }

        // Singleton instance — only one database connection across the app
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kasds_database"
                )
                    .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                INSTANCE = instance
                instance
            }
        }

    }
}
