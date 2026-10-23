package com.apcida.smishingdetector.backend.detection

import java.net.URI

/** Extracts links and bare domains before URL evidence is matched or scored. */
object UrlCandidates {
    data class Candidate(val host: String, val hasUserInfo: Boolean)

    private val urlPattern = Regex(
        """(?i)(?<![@\p{L}\p{N}._-])(?:https?://(?:[^\s<>"'/?#@]+@)?|www\.)?(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}|(?:\d{1,3}\.){3}\d{1,3})(?::\d{1,5})?(?:[/?#][^\s<>"']*)?"""
    )

    fun extract(messageBody: String): List<Candidate> = urlPattern.findAll(messageBody)
        .mapNotNull { match ->
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            val parseable = if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
            runCatching {
                val uri = URI(parseable)
                uri.host?.lowercase()?.let { Candidate(it, uri.userInfo != null) }
            }.getOrNull()
        }
        .toList()

    /** Seed entries name a host, a top-level-domain suffix, or a complete host label. */
    fun matchesHost(host: String, pattern: String): Boolean {
        val candidate = pattern.trim().lowercase()
        if (candidate.isEmpty()) return false
        return when {
            candidate.startsWith(".") -> host.endsWith(candidate) && host.length > candidate.length
            candidate.contains('.') -> host == candidate || host.endsWith(".$candidate")
            else -> host.split('.').any { it == candidate }
        }
    }
}
