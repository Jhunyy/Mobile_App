package com.apcida.smishingdetector.util

import java.security.MessageDigest

object HashUtil {

    /**
     * Generates a SHA-256 hash of the given input string.
     * Used to create content_hash for duplicate message detection.
     */
    fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.trim().lowercase().toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    /**
     * Generates a hash of the sender string for privacy protection.
     * Raw phone numbers are never stored — only their hash.
     */
    fun hashSender(sender: String): String {
        return sha256(sender.trim())
    }

    /**
     * Generates a hash of the message content.
     * Used to detect and skip duplicate incoming messages.
     */
    fun hashContent(content: String): String {
        return sha256(content.trim())
    }
}