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
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun keywordDao(): KeywordDao
    abstract fun messageKeywordDao(): MessageKeywordDao
    abstract fun reportDao(): ReportDao
    abstract fun safetyTipDao(): SafetyTipDao

    companion object {

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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN deterministic_risk_level TEXT NOT NULL DEFAULT 'SAFE'"
                )
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN matched_indicators TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN ai_analysis_status TEXT NOT NULL DEFAULT 'NOT_STARTED'"
                )
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN processing_state TEXT NOT NULL DEFAULT 'COMPLETED'"
                )
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN final_classification TEXT NOT NULL DEFAULT 'SAFE'"
                )
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
    }
}
