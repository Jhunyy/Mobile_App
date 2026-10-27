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

                val weight = when {
                    host.startsWith("xn--") || ipAddress.matches(host) || url.hasUserInfo -> 30f
                    host.count { it == '-' } >= 2 -> 15f
                    else -> 5f
                }

                Keyword(
                    keywordId = 0,
                    pattern = "URL:$host",
                    patternType = "URL_PATTERN",
                    weight = weight,
                    language = "UNIVERSAL"
                )
            }
            .distinctBy { it.pattern }
            .toList()
    }
}
