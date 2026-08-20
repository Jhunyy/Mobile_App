package com.apcida.smishingdetector.backend.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.apcida.smishingdetector.controller.SmsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {

        // Only process SMS_RECEIVED broadcasts
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Extract all SMS messages from the intent
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Received SMS broadcast but no messages found.")
            return
        }

        // Group multi-part messages by sender and combine their bodies
        val groupedMessages = mutableMapOf<String, StringBuilder>()

        for (smsMessage in messages) {
            val sender = smsMessage.originatingAddress ?: "Unknown"
            val body = smsMessage.messageBody ?: ""
            groupedMessages.getOrPut(sender) { StringBuilder() }.append(body)
        }

        // Pass each complete message to SmsController for detection pipeline
        val controller = SmsController(context)

        CoroutineScope(Dispatchers.IO).launch {
            groupedMessages.forEach { (sender, bodyBuilder) ->
                val messageBody = bodyBuilder.toString().trim()

                if (messageBody.isNotEmpty()) {
                    Log.d(TAG, "New SMS received from: $sender")
                    controller.onSmsReceived(sender, messageBody)
                }
            }
        }
    }
}