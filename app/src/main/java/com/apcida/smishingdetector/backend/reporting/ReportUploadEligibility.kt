package com.apcida.smishingdetector.backend.reporting

import android.content.Context

/** Keeps reports from older builds or prior installations local after an app upgrade. */
object ReportUploadEligibility {
    private const val PREFS = "report_upload_eligibility_v1"
    private const val CUTOFF = "eligible_after"

    fun initialize(context: Context): Long {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = preferences.getLong(CUTOFF, 0L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        check(preferences.edit().putLong(CUTOFF, now).commit()) {
            "Could not establish report upload consent boundary"
        }
        return now
    }

    fun beginNewEnrollment(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(preferences.edit().putLong(CUTOFF, System.currentTimeMillis()).commit()) {
            "Could not establish enrollment report boundary"
        }
    }

    fun isEligible(context: Context, reportedAt: Long): Boolean =
        reportedAt >= initialize(context)
}
