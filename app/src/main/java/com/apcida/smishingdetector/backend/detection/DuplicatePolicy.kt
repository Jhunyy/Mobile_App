package com.apcida.smishingdetector.backend.detection

object DuplicatePolicy {
    const val WINDOW_MILLIS: Long = 10 * 60 * 1000L

    fun windowStart(receivedAt: Long): Long = receivedAt - WINDOW_MILLIS

    fun isWithinWindow(previousReceivedAt: Long, receivedAt: Long): Boolean =
        previousReceivedAt in windowStart(receivedAt)..receivedAt
}
