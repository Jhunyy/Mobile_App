package com.apcida.smishingdetector.backend.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
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
                    .fallbackToDestructiveMigration() // for development only
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}