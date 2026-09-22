package com.apcida.smishingdetector.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDeepLinkBuilder
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.model.data.DetectionResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.view.activity.MainActivity

object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val SCAM_ALERT_CHANNEL_ID = "scam_alerts"
    private const val SCAM_ALERT_CHANNEL_NAME = "Scam Alerts"

    fun showScamAlert(
        context: Context,
        result: DetectionResult,
        messageId: Long
    ) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "Notification permission not granted. Scam alert not shown.")
            return
        }

        createScamAlertChannel(context)

        val pendingIntent = NavDeepLinkBuilder(context)
            .setComponentName(MainActivity::class.java)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.messageDetailFragment)
            .setArguments(Bundle().apply {
                putLong("messageId", messageId)
            })
            .createPendingIntent()

        val isRuleOnlyWarning = !result.gemmaInvoked
        val title = when {
            isRuleOnlyWarning -> "High-risk SMS detected"
            result.riskLevel == RiskLevel.SUSPICIOUS -> "Suspicious SMS requires review"
            else -> "Potential scam SMS detected"
        }
        val explanation = when {
            isRuleOnlyWarning ->
                "Rule analysis found strong scam indicators. AI analysis will refine the result."
            result.riskLevel == RiskLevel.SUSPICIOUS ->
                "AI reduced the initial high-risk warning to suspicious, but the message is not safe."
            else ->
                "Rule and AI results indicate a scam. Review the result before taking action."
        }
        val notification = NotificationCompat.Builder(context, SCAM_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText("Risk score: ${result.riskScore.toInt()}. Tap to review details.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(explanation)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context)
            .notify(messageId.toInt(), notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        val notificationsEnabled = NotificationManagerCompat.from(context)
            .areNotificationsEnabled()

        if (!notificationsEnabled) return false

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createScamAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            SCAM_ALERT_CHANNEL_ID,
            SCAM_ALERT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when KA-SDS detects a likely scam SMS."
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
