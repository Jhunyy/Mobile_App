package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.entity.Keyword

/** Caps legacy seeded weights too, since existing installations keep their first-launch seed. */
object RuleWeightPolicy {
    private val contextOnly = setOf(
        "gcash", "bdo", "bpi", "metrobank", "your account", "bank account",
        "your parcel", "your package", "ang iyong package", "ang iyong parcel",
        "ang iyong pakete", "imong parcel", "imong package", "imong bank account"
    )
    private val promotional = setOf(
        "winner", "reward", "free gift", "manalo", "premyo", "libreng",
        "libre nga regalo", "personal information", "social security", "credit card number"
    )
    private val prizeClaims = setOf(
        "you have won", "you won", "claim your prize", "claim prize", "congratulations you",
        "nanalo ka", "napanalunan mo", "panalo ka", "kunin ang premyo",
        "i-claim ang premyo", "may premyo ka", "nakadaog ka", "daog ka",
        "premyo nimo", "kuhaa ang premyo", "i-claim ang imong premyo"
    )
    private val routineService = setOf(
        "pending delivery", "failed delivery", "reschedule delivery", "track your package",
        "delivery fee", "hindi na-deliver", "nabigong delivery", "i-reschedule ang iyong delivery",
        "wala nadeliver", "napakyas ang delivery", "usba ang schedule sa delivery",
        "i-track imong package"
    )
    private val genericUrgency = setOf(
        "act now", "act immediately", "expires today", "limited time", "last chance",
        "ngayon na", "huwag palampasin", "mag-claim na", "kailangan ngayon",
        "sa loob ng 24 oras", "karon na", "ayaw palabya", "sulod sa 24 oras",
        "karong adlawa"
    )
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "rb.gy", "shorturl.at",
        "cutt.ly", "is.gd", "ow.ly", "buff.ly"
    )

    fun weight(keyword: Keyword): Float {
        val pattern = keyword.pattern.lowercase().trim()
        val cap = when {
            keyword.patternType == "URL_PATTERN" && pattern.startsWith(".") -> 10f
            keyword.patternType == "URL_PATTERN" && pattern in shorteners -> 15f
            keyword.patternType == "URL_PATTERN" && pattern.startsWith("URL:") &&
                keyword.weight <= 10f -> 5f
            keyword.patternType == "URL_PATTERN" -> 30f
            pattern in contextOnly -> 0f
            pattern in promotional -> 5f
            pattern in prizeClaims -> 20f
            pattern in routineService -> 10f
            pattern in genericUrgency -> 12f
            keyword.patternType == "KEYWORD" && pattern.split(Regex("\\s+")).size == 1 -> 8f
            else -> keyword.weight
        }
        return keyword.weight.coerceAtMost(cap)
    }
}
