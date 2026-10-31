package com.apcida.smishingdetector

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.controller.SmsController
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.NotificationHelper
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Invented text through the same local intake method used by the SMS receiver; no SMS broadcast or network request. */
@RunWith(AndroidJUnit4::class)
class SyntheticIncomingSmsInstrumentedTest {
    @Test fun localWarningSurvivesDuplicateWhileRemoteGateIsClosed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        val preferences = context.getSharedPreferences(Constants.PREFS_NAME, 0)
        val hadAlertSetting = preferences.contains(Constants.PREF_ALERTS_ENABLED)
        val originalAlertSetting = preferences.getBoolean(Constants.PREF_ALERTS_ENABLED, true)
        val dao = AppDatabase.getInstance(context).messageDao()
        val controller = SmsController(context)
        var messageId = 0L

        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            assertTrue("Notifications are disabled for the ApCiDa debug app",
                NotificationManagerCompat.from(context).areNotificationsEnabled())
            assertTrue(preferences.edit().putBoolean(Constants.PREF_ALERTS_ENABLED, true).commit())
            assertFalse(RemoteTransportGate.PRODUCTION_READY)

            val sender = "SyntheticBank-${UUID.randomUUID()}"
            val body = "We are BDO security team. Reply with your OTP now to stop account suspension."
            val receivedAt = System.currentTimeMillis()
            val first = controller.receiveAndAnalyzeRules(sender, body, receivedAt)
            messageId = first.messageId
            assertTrue(messageId > 0)
            assertFalse("A closed production gate must not enqueue remote analysis", first.shouldEnqueueAi)

            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (notifications.activeNotifications.none { it.id == messageId.toInt() } &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            val warning = notifications.activeNotifications.filter { it.id == messageId.toInt() }
            assertEquals("Expected one local warning", 1, warning.size)
            assertEquals("High-risk SMS detected",
                warning.single().notification.extras.getString("android.title"))

            val duplicate = controller.receiveAndAnalyzeRules(sender, body, receivedAt)
            assertEquals(messageId, duplicate.messageId)
            assertFalse(duplicate.shouldEnqueueAi)
            assertEquals(1, notifications.activeNotifications.count { it.id == messageId.toInt() })

            val stored = dao.getMessageById(messageId)!!
            assertEquals("SCAM", stored.finalClassification)
            assertEquals("LOCAL_RULES", stored.verdictSource)
            assertTrue(stored.notificationPosted)
            assertFalse(stored.processingState == ProcessingState.AI_QUEUED.name)
            assertFalse(stored.processingState == ProcessingState.AI_ANALYZING.name)
        } finally {
            if (messageId > 0) {
                NotificationHelper.cancelAlert(context, messageId)
                dao.getMessageById(messageId)?.let { row ->
                    AppDatabase.getInstance(context).messageKeywordDao()
                        .deleteMatchesForMessage(messageId)
                    dao.deleteMessage(row)
                }
            }
            val editor = preferences.edit()
            if (hadAlertSetting) editor.putBoolean(Constants.PREF_ALERTS_ENABLED, originalAlertSetting)
            else editor.remove(Constants.PREF_ALERTS_ENABLED)
            assertTrue(editor.commit())
        }
    }
}
