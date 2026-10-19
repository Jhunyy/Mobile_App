package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword
import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordMatcherTest {
    @Test
    fun matchesWholePhrasesWithoutCountingTheirSubphrases() {
        val keywords = listOf(
            keyword("verify", 10f),
            keyword("verify your account", 20f),
            keyword("bdo", 15f)
        )

        val matches = KeywordMatcher.match("Please verify your account today.", keywords)

        assertEquals(listOf("verify your account"), matches.map { it.pattern })
        assertEquals(emptyList<Keyword>(), KeywordMatcher.match("The abdominoplasty appointment is set.", keywords))
    }

    @Test
    fun urlPatternsMatchParsedHostsIncludingBareDomains() {
        val keywords = listOf(
            Keyword(pattern = "bit.ly", patternType = "URL_PATTERN", weight = 35f),
            Keyword(pattern = ".xyz", patternType = "URL_PATTERN", weight = 30f)
        )

        assertEquals(listOf("bit.ly"), KeywordMatcher.match("Open bit.ly/abc", keywords).map { it.pattern })
        assertEquals(listOf("bit.ly"), KeywordMatcher.match("Open https://bit.ly/abc", keywords).map { it.pattern })
        assertEquals(listOf(".xyz"), KeywordMatcher.match("Open https://parcel.xyz/pay", keywords).map { it.pattern })
        assertEquals(emptyList<Keyword>(), KeywordMatcher.match("Open https://notbit.ly.example/pay", keywords))
        assertEquals(emptyList<Keyword>(), KeywordMatcher.match("Email help@bit.ly for assistance", keywords))
    }

    private fun keyword(pattern: String, weight: Float) =
        Keyword(pattern = pattern, patternType = "KEYWORD", weight = weight)
}
