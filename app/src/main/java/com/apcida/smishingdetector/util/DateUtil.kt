package com.apcida.smishingdetector.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtil {

    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatTimeShort(timestamp: Long): String {
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatDateOnly(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}