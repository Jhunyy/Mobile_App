package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword
import java.net.URI

/** Adds URL evidence even when a URL is not in the seeded domain list. */
object UrlIndicatorDetector {
    private val urlPattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>\"']+""")
    private val ipAddress = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    fun detect(messageBody: String, existingMatches: List<Keyword>): List<Keyword> {
        val existingPatterns = existingMatches
            .filter { it.patternType == "URL_PATTERN" }
            .map { it.pattern.lowercase() }

        return urlPattern.findAll(messageBody)
            .mapNotNull { match ->
                val rawUrl = match.value.trimEnd('.', ',', ')', ']', '!', '?')
                if (existingPatterns.any { rawUrl.contains(it, ignoreCase = true) }) {
                    return@mapNotNull null
                }

                val parseable = if (rawUrl.startsWith("www.", ignoreCase = true)) {
                    "https://$rawUrl"
                } else {
                    rawUrl
                }
                val host = runCatching { URI(parseable).host?.lowercase() }.getOrNull()
                    ?: return@mapNotNull null
                val suspicious = host.startsWith("xn--") ||
                    ipAddress.matches(host) ||
                    host.count { it == '-' } >= 2 ||
                    parseable.substringAfter("://", "").substringBefore('/').contains('@')

                Keyword(
                    keywordId = 0,
                    pattern = "URL:$host",
                    patternType = "URL_PATTERN",
                    weight = if (suspicious) 40f else 10f,
                    language = "UNIVERSAL"
                )
            }
            .distinctBy { it.pattern }
            .toList()
    }
}
