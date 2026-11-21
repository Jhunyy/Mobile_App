package com.apcida.smishingdetector.backend.community

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Local candidate filtering for collection, not a source of accuracy ground truth. */
object CommunityPolicy {
    const val TOTAL_LIMIT = 40
    const val MAX_MESSAGE_LENGTH = 2000

    private val shortCode = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val longNumber = Regex("(?<!\\d)\\d{9,}(?!\\d)")
    private val separatedNumber = Regex("(?<!\\d)(?:\\d[\\s().-]?){8,}\\d(?!\\d)")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val url = Regex("(?i)(?:https?://|www\\.)\\S+")
    private val phone = Regex("(?<!\\d)(?:\\+?63|0)9\\d{9}(?!\\d)")

    fun eligible(body: String): Boolean = body.isNotBlank() &&
        body.length <= MAX_MESSAGE_LENGTH &&
        !shortCode.containsMatchIn(body) &&
        !longNumber.containsMatchIn(body) &&
        !separatedNumber.containsMatchIn(body)

    fun redact(body: String): String = phone.replace(
        shortCode.replace(separatedNumber.replace(longNumber.replace(email.replace(url.replace(body, "[link]"), "[email]"), "[number]"), "[number]"), "[code]"),
        "[phone]"
    ).trim()

    fun fingerprint(body: String, privateKey: ByteArray? = null): String {
        val normalized = body.trim().lowercase().replace(Regex("\\s+"), " ")
        if (privateKey == null) return sha256(normalized)
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(privateKey, "HmacSHA256"))
        }.doFinal(normalized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun template(body: String): String = body.lowercase()
        .replace(url, "[link]")
        .replace(Regex("\\d+"), "#")
        .replace(Regex("\\s+"), " ").trim()

    fun remaining(accepted: Int): Int = (TOTAL_LIMIT - accepted).coerceIn(0, TOTAL_LIMIT)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
