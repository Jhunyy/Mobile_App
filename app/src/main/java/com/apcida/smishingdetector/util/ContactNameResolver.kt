package com.apcida.smishingdetector.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContactNameResolver {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean =
        hasPermission(context) && context.getSharedPreferences(Constants.PREFS_NAME, 0)
            .getBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, false)

    suspend fun findName(context: Context, address: String): String? = withContext(Dispatchers.IO) {
        if (!hasPermission(context) || !address.any(Char::isDigit) || address.any(Char::isLetter)) {
            return@withContext null
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }
                else null
            }
        } catch (_: SecurityException) {
            null // Permission can be revoked while a lookup is running.
        }
    }
}
