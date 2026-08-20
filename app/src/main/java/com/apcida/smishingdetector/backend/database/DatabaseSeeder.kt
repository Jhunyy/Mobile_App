package com.apcida.smishingdetector.backend.database

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.model.entity.Keyword
import com.apcida.smishingdetector.model.entity.SafetyTip
import com.apcida.smishingdetector.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class DatabaseSeeder(
    private val context: Context,
    private val database: AppDatabase
) {

    companion object {
        private const val TAG = "DatabaseSeeder"
    }

    /**
     * Seeds the database on first launch.
     * Reads keywords and safety tips from XML resource files
     * and inserts them into the local SQLite database.
     */
    suspend fun seedIfFirstLaunch() {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val isFirstLaunch = prefs.getBoolean(
                Constants.PREF_IS_FIRST_LAUNCH,
                true
            )

            if (isFirstLaunch) {
                Log.d(TAG, "First launch detected. Seeding database...")
                seedKeywords()
                seedSafetyTips()
                prefs.edit()
                    .putBoolean(Constants.PREF_IS_FIRST_LAUNCH, false)
                    .apply()
                Log.d(TAG, "Database seeding complete.")
            } else {
                Log.d(TAG, "Database already seeded. Skipping.")
            }
        }
    }

    /**
     * Reads keywords_seed.xml and inserts all keyword entries
     * into the keywords table.
     */
    private suspend fun seedKeywords() {
        val keywords = mutableListOf<Keyword>()

        try {
            val parser = getXmlParser("keywords_seed.xml")
            var eventType = parser.eventType

            var pattern = ""
            var patternType = ""
            var weight = 0f
            var language = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "keyword" -> {
                                pattern = parser.getAttributeValue(null, "pattern") ?: ""
                                patternType = parser.getAttributeValue(null, "type") ?: "KEYWORD"
                                weight = parser.getAttributeValue(null, "weight")
                                    ?.toFloatOrNull() ?: 10f
                                language = parser.getAttributeValue(null, "language") ?: "ENGLISH"
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "keyword" && pattern.isNotEmpty()) {
                            keywords.add(
                                Keyword(
                                    pattern = pattern,
                                    patternType = patternType,
                                    weight = weight,
                                    language = language,
                                    isActive = true
                                )
                            )
                            pattern = ""
                        }
                    }
                }
                eventType = parser.next()
            }

            database.keywordDao().insertAllKeywords(keywords)
            Log.d(TAG, "Seeded ${keywords.size} keywords.")

        } catch (e: Exception) {
            Log.e(TAG, "Error seeding keywords: ${e.message}")
        }
    }

    /**
     * Reads safety_tips_seed.xml and inserts all tip entries
     * into the safety_tips table.
     */
    private suspend fun seedSafetyTips() {
        val tips = mutableListOf<SafetyTip>()

        try {
            val parser = getXmlParser("safety_tips_seed.xml")
            var eventType = parser.eventType

            var title = ""
            var content = ""
            var category = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "tip" -> {
                                title = parser.getAttributeValue(null, "title") ?: ""
                                category = parser.getAttributeValue(null, "category") ?: "GENERAL"
                            }
                            "content" -> {
                                eventType = parser.next()
                                content = parser.text ?: ""
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "tip" && title.isNotEmpty()) {
                            tips.add(
                                SafetyTip(
                                    title = title,
                                    content = content,
                                    category = category,
                                    isActive = true
                                )
                            )
                            title = ""
                            content = ""
                        }
                    }
                }
                eventType = parser.next()
            }

            database.safetyTipDao().insertAllTips(tips)
            Log.d(TAG, "Seeded ${tips.size} safety tips.")

        } catch (e: Exception) {
            Log.e(TAG, "Error seeding safety tips: ${e.message}")
        }
    }

    /**
     * Creates an XmlPullParser from a file in the assets folder.
     */
    private fun getXmlParser(fileName: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(context.assets.open(fileName), "UTF-8")
        return parser
    }
}