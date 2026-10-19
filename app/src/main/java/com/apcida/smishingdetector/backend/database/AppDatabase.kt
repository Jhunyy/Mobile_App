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
    version = 3,
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
                    .addMigrations(MIGRATION_1_3, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }

    }
}
