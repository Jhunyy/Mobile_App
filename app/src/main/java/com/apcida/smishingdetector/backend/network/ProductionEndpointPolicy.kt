package com.apcida.smishingdetector.backend.network

import java.net.URI
import java.util.Locale

/** Checks a configured origin against a separately approved DNS identity. No network request. */
object ProductionEndpointPolicy {
    fun isApprovedHttpsOrigin(endpoint: String?, approvedHost: String?): Boolean {
        if (endpoint == null || approvedHost.isNullOrBlank() || endpoint != endpoint.trim()) return false
        val uri = try { URI(endpoint) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val expected = approvedHost.lowercase(Locale.ROOT)
        val ipv4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""").matches(host)
        return uri.scheme.equals("https", ignoreCase = true) && host == expected &&
            host.contains('.') && !ipv4 && ':' !in host &&
            host != "localhost" && !host.endsWith(".local") && !host.endsWith(".internal") &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            (uri.port == -1 || uri.port == 443)
    }
}
