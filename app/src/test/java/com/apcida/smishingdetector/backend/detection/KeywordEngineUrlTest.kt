package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.backend.database.dao.KeywordDao
import com.apcida.smishingdetector.model.entity.Keyword
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordEngineUrlTest {
    @Test
    fun oneHostContributesOnlyItsStrongestSeededUrlPattern() = runBlocking {
        val keywords = listOf(
            Keyword(pattern = ".xyz", patternType = "URL_PATTERN", weight = 30f),
            Keyword(pattern = "bdo-verify", patternType = "URL_PATTERN", weight = 40f)
        )
        val dao = Proxy.newProxyInstance(
            KeywordDao::class.java.classLoader, arrayOf(KeywordDao::class.java)
        ) { _, method, _ ->
            if (method.name == "getAllActiveKeywords") keywords else error("Unexpected DAO call")
        } as KeywordDao

        val matches = KeywordEngine(dao).analyze("Open https://bdo-verify.xyz/pay")
        assertEquals(listOf("bdo-verify"), matches.map { it.pattern })
        assertEquals(30f, RiskScorer().compute(matches), 0.001f)
    }

    @Test
    fun urlPatternsDoNotScoreTextOutsideExtractedHosts() = runBlocking {
        val keywords = listOf(
            Keyword(pattern = "bit.ly", patternType = "URL_PATTERN", weight = 35f),
            Keyword(pattern = ".xyz", patternType = "URL_PATTERN", weight = 30f)
        )
        val dao = Proxy.newProxyInstance(
            KeywordDao::class.java.classLoader,
            arrayOf(KeywordDao::class.java)
        ) { _, method, _ ->
            if (method.name == "getAllActiveKeywords") keywords else error("Unexpected DAO call: ${method.name}")
        } as KeywordDao

        val matches = KeywordEngine(dao).analyze(
            "See https://example.com/bit.ly and email help@parcel.xyz"
        )

        assertEquals(listOf("URL:example.com"), matches.map { it.pattern })
        assertEquals(5f, RiskScorer().compute(matches), 0.001f)
    }
}
