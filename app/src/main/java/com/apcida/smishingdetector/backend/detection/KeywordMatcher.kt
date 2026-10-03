package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword
import java.net.URI

/** Matches complete phrases and URL hosts, avoiding matches inside unrelated words. */
object KeywordMatcher {
    private val urlRegex = Regex(
        "(?i)(?<![@\\p{L}\\p{N}-])(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:/[^\\s<>()]*)?"
    )

    fun match(message: String, keywords: List<Keyword>): List<Keyword> {
        val hosts = urlRegex.findAll(message).mapNotNull { candidate ->
            val url = candidate.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
            runCatching {
                URI(if (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true)) url else "https://$url")
                    .host?.lowercase()
            }.getOrNull()
        }.toList()

        val phraseMatches = keywords.asSequence()
            .filter { it.isActive && it.patternType == "KEYWORD" && it.pattern.isNotBlank() }
            .mapNotNull { keyword ->
                val escaped = Regex.escape(keyword.pattern.trim())
                val range = Regex("(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                    .find(message)?.range
                range?.let { keyword to it }
            }
            .sortedWith(compareByDescending<Pair<Keyword, IntRange>> { it.second.last - it.second.first }
                .thenByDescending { it.first.weight })
            .toList()

        val selectedRanges = mutableListOf<IntRange>()
        val selectedPhrases = phraseMatches.filter { (_, range) ->
            if (selectedRanges.any { it.first <= range.last && range.first <= it.last }) false
            else {
                selectedRanges.add(range)
                true
            }
        }.map { it.first }

        val urlMatches = keywords.filter { keyword ->
            if (!keyword.isActive || keyword.patternType != "URL_PATTERN") return@filter false
            val pattern = keyword.pattern.trim().lowercase()
            if (pattern.isEmpty()) return@filter false
            hosts.any { host ->
                when {
                    pattern.startsWith(".") -> host.endsWith(pattern)
                    pattern.contains('.') -> host == pattern || host.endsWith(".$pattern")
                    else -> host.split('.').any { it == pattern }
                }
            }
        }

        return selectedPhrases + urlMatches
    }
}
