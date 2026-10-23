package com.apcida.smishingdetector.backend.detection

object KeywordPatternMatcher {
    fun matches(messageBody: String, pattern: String, patternType: String?): Boolean {
        val candidate = pattern.trim()
        if (candidate.isEmpty()) return false

        if (patternType == "URL_PATTERN") {
            return UrlCandidates.extract(messageBody).any { UrlCandidates.matchesHost(it.host, candidate) }
        }

        val boundedPattern = Regex(
            "(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        )
        return boundedPattern.containsMatchIn(messageBody)
    }
}
