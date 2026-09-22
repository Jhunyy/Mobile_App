package com.apcida.smishingdetector.backend.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.apcida.smishingdetector.backend.worker.SmsProcessingWorker
import com.apcida.smishingdetector.controller.SmsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Received SMS broadcast without message parts.")
            return
        }

        val groupedMessages = mutableMapOf<String, StringBuilder>()
        messages.forEach { smsMessage ->
            val sender = smsMessage.originatingAddress ?: "Unknown"
            groupedMessages.getOrPut(sender) { StringBuilder() }
                .append(smsMessage.messageBody.orEmpty())
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val controller = SmsController(appContext)
                groupedMessages.forEach { (sender, bodyBuilder) ->
                    val body = bodyBuilder.toString().trim()
                    if (body.isNotEmpty()) {
                        val result = controller.receiveAndAnalyzeRules(sender, body)
                        if (result.shouldEnqueueAi) {
                            SmsProcessingWorker.enqueue(appContext, result.messageId)
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist and queue an incoming SMS.", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
