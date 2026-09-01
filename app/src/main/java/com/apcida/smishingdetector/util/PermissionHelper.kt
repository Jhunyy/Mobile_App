package com.apcida.smishingdetector.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    const val SMS_PERMISSION_REQUEST_CODE = 101
    const val NOTIFICATION_PERMISSION_REQUEST_CODE = 102

    /**
     * Checks if both RECEIVE_SMS and READ_SMS
     * permissions are granted.
     */
    fun hasSmsPermissions(context: Context): Boolean {
        return hasReceiveSmsPermission(context) &&
                hasReadSmsPermission(context)
    }

    /**
     * Checks if RECEIVE_SMS permission is granted.
     */
    fun hasReceiveSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if READ_SMS permission is granted.
     */
    fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests both SMS permissions from the user.
     * Result is delivered to onRequestPermissionsResult
     * in the calling Activity.
     */
    fun requestSmsPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ),
            SMS_PERMISSION_REQUEST_CODE
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Checks if the permission request result
     * means all permissions were granted.
     */
    fun allPermissionsGranted(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Returns true if the user has previously denied
     * the permission and checked "Don't ask again."
     * Use this to decide whether to show a settings
     * redirect dialog instead of requesting again.
     */
    fun shouldShowSettingsRedirect(
        activity: Activity,
        permission: String
    ): Boolean {
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            permission
        ) && ContextCompat.checkSelfPermission(
            activity,
            permission
        ) != PackageManager.PERMISSION_GRANTED
    }
}
