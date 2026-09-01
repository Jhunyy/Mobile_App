package com.apcida.smishingdetector.util

object Constants {

    // ── Detection Threshold ──────────────────────────────────────
    // Messages with risk score >= this value trigger Stage 2 Gemma
    // Calibrate this during testing — start at 60
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

    // ── Report Types ─────────────────────────────────────────────
    const val REPORT_PHISHING_LINK = "Phishing Link (Fake Login Page)"
    const val REPORT_SUSPICIOUS_URL = "Suspicious or Malicious URL"
    const val REPORT_SHORTENED_URL = "Shortened or Masked URL"
    const val REPORT_DELIVERY_SCAM = "Delivery/Parcel Scam Link"
    const val REPORT_URGENT_MESSAGE = "Urgent or Threatening Message"
    const val REPORT_OTHER = "Others"

    // ── Network ──────────────────────────────────────────────────
    // Replace with your actual server URL when ready
    const val BASE_URL = "https://your-report-server.com/api/"
    const val REPORT_ENDPOINT = "reports/submit"
    const val REPORT_API_KEY = ""
    const val REPORT_API_KEY_HEADER = "X-API-Key"
    const val REPORT_TIMESTAMP_HEADER = "X-Timestamp"

    // ── Gemma Model ──────────────────────────────────────────────
    const val GEMMA_MODEL_PATH = "gemma/gemma3-1b-it-int4.task"
    const val GEMMA_MAX_TOKENS = 200
    //const val GEMMA_TEMPERATURE = 0.1f  // low = more deterministic output

    // ── Database ─────────────────────────────────────────────────
    const val DATABASE_NAME = "kasds_database"

    // ── SharedPreferences ────────────────────────────────────────
    const val PREFS_NAME = "kasds_prefs"
    const val PREF_IS_FIRST_LAUNCH = "is_first_launch"
    const val PREF_SMS_PERMISSION_GRANTED = "sms_permission_granted"
}
