package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword

/** Adds URL evidence even when a URL is not in the seeded domain list. */
object UrlIndicatorDetector {
    private val ipAddress = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    fun detect(messageBody: String, existingMatches: List<Keyword>): List<Keyword> =
        detect(UrlCandidates.extract(messageBody), existingMatches)

    fun detect(urls: List<UrlCandidates.Candidate>, existingMatches: List<Keyword>): List<Keyword> {
        val existingPatterns = existingMatches
            .filter { it.patternType == "URL_PATTERN" }
            .map { it.pattern }

        return urls.asSequence()
            .mapNotNull { url ->
                val host = url.host
                if (existingPatterns.any { UrlCandidates.matchesHost(host, it) }) {
                    return@mapNotNull null
                }

                val suspicious = host.startsWith("xn--") ||
                    ipAddress.matches(host) ||
                    host.count { it == '-' } >= 2 ||
                    url.hasUserInfo

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
