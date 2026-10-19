package com.apcida.smishingdetector.util

import com.apcida.smishingdetector.BuildConfig

object Constants {

    // ── Detection Threshold ──────────────────────────────────────
    // Messages with risk score >= this value are high risk by deterministic
    // analysis. Gemma is invoked for every message regardless of this value.
    const val RISK_THRESHOLD = 60f

    // ── Keyword Weight Reference ─────────────────────────────────
    // These are defaults — actual weights are stored in the database
    // and loaded from keywords_seed.xml on first launch
    const val WEIGHT_PRIZE_REWARD = 30f
    const val WEIGHT_URGENCY = 25f
    const val WEIGHT_BANK_IMPERSONATION = 20f
    const val WEIGHT_SHORTENED_URL = 35f
    const val WEIGHT_SUSPICIOUS_DOMAIN = 40f
    const val WEIGHT_AMBIGUOUS = 10f

    // ── Risk Level Labels ────────────────────────────────────────
    const val RISK_SAFE = "SAFE"
    const val RISK_SUSPICIOUS = "SUSPICIOUS"
    const val RISK_SCAM = "SCAM"

    // ── Gemma Classification Labels ──────────────────────────────
    const val GEMMA_SCAM = "SCAM"
    const val GEMMA_LEGITIMATE = "LEGITIMATE"
    const val GEMMA_UNCERTAIN = "UNCERTAIN"

    // ── Gemma Confidence Labels ──────────────────────────────────
    const val CONFIDENCE_HIGH = "HIGH"
    const val CONFIDENCE_MEDIUM = "MEDIUM"
    const val CONFIDENCE_LOW = "LOW"

    // ── Optional detection feedback ────────────────────────────
    const val REPORT_NOT_SCANNED = "Message was not scanned"
    const val REPORT_MISSED_SCAM = "Scam was not detected"
    const val REPORT_FALSE_ALERT = "Legitimate message was flagged"
    const val REPORT_OTHER = "Other detection issue"

    // ── Network ──────────────────────────────────────────────────
    val BASE_URL = BuildConfig.REPORT_BASE_URL
    const val REPORT_ENDPOINT = "reports/submit"
    val REPORT_API_KEY = BuildConfig.REPORT_API_KEY
    const val REPORT_API_KEY_HEADER = "X-API-Key"
    const val REPORT_TIMESTAMP_HEADER = "X-Timestamp"

    // ── Gemma Model ──────────────────────────────────────────────
    const val GEMMA_MODEL_PATH = "gemma/gemma3-1b-it-int4.task"
    const val GEMMA_MAX_TOKENS = 200
    const val GEMMA_TIMEOUT_MILLIS = 90_000L
    //const val GEMMA_TEMPERATURE = 0.1f  // low = more deterministic output

    // ── Database ─────────────────────────────────────────────────
    const val DATABASE_NAME = "kasds_database"

    // ── SharedPreferences ────────────────────────────────────────
    const val PREFS_NAME = "kasds_prefs"
    const val PREF_IS_FIRST_LAUNCH = "is_first_launch"
    const val PREF_SMS_PERMISSION_GRANTED = "sms_permission_granted"
    const val PREF_CONTACT_NAMES_ENABLED = "contact_names_enabled"
    const val PREF_CONTACT_PROMPT_SHOWN = "contact_prompt_shown"
}
