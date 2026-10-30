package com.apcida.smishingdetector.util

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic notifications only. No receiver, inbox, network, or production data is used. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 23)
class SyntheticNotificationInstrumentedTest {
    @Test fun localWarningIsUpdatedOnceAndSurvivesUnavailableAnalysis() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Enable notifications for the ApCiDa debug app before this test",
            NotificationManagerCompat.from(context).areNotificationsEnabled())
        assumeTrue("Grant POST_NOTIFICATIONS to the ApCiDa debug app before this test",
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Enable scam alerts in ApCiDa Settings before this test",
            context.getSharedPreferences(Constants.PREFS_NAME, 0)
                .getBoolean(Constants.PREF_ALERTS_ENABLED, true))

        val id = 2_000_003_001L
        fun active() = manager.activeNotifications.filter { it.id == id.toInt() }
        try {
            NotificationHelper.showScamAlert(context, DetectionResult(
                messageContent = "Synthetic local warning", riskScore = 80f,
                riskLevel = RiskLevel.SCAM, isFlagged = true, matchedKeywords = emptyList(),
                finalClassification = "SCAM", finalRationale = null), id)
            assertEquals(1, active().size)
            val localTitle = active().single().notification.extras.getString("android.title")
            assertEquals("High-risk SMS detected", localTitle)

            // Timeout/network failure leaves the local warning in place; no SAFE notice is posted.
            assertEquals(1, active().size)
            NotificationHelper.showScamAlert(context, DetectionResult(
                messageContent = "Synthetic remote warning", riskScore = 80f,
                riskLevel = RiskLevel.SCAM, isFlagged = true, matchedKeywords = emptyList(),
                gemmaInvoked = true, finalClassification = "SCAM",
                finalRationale = "Synthetic mock response"), id)
            assertEquals(1, active().size)
            assertEquals("Potential scam SMS detected",
                active().single().notification.extras.getString("android.title"))
            assertTrue(active().single().notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
            NotificationHelper.cancelAlert(context, id)
            // The system notification service processes cancellation on its handler.
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (active().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue("Scam alert remained active after cancellation", active().isEmpty())
        } finally { NotificationHelper.cancelAlert(context, id) }
    }
}
