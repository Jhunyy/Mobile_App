package com.apcida.smishingdetector.view.dialog

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.apcida.smishingdetector.databinding.DialogScamAlertBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.Constants

class ScamAlertDialog(
    private val context: Context,
    private val message: Message,
    private val matchedKeywords: List<String> = emptyList(),
    private val onReport: () -> Unit,
    private val onDelete: () -> Unit,
    private val onIgnore: () -> Unit
) {

    fun show() {
        val binding = DialogScamAlertBinding.inflate(
            LayoutInflater.from(context)
        )

        // Risk score
        binding.textAlertRiskScore.text =
            "Risk Score: ${message.riskScore.toInt()}"

        // Matched keywords
        if (matchedKeywords.isNotEmpty()) {
            binding.textAlertKeywords.text =
                "Keywords: ${matchedKeywords.joinToString(", ")}"
            binding.textAlertKeywords.visibility = View.VISIBLE
        } else {
            binding.textAlertKeywords.visibility = View.GONE
        }

        // Gemma analysis — only show if available
        if (message.gemmaInvoked && !message.gemmaRationale.isNullOrEmpty()) {
            binding.cardAlertGemma.visibility = View.VISIBLE
            binding.textAlertGemmaRationale.text = message.gemmaRationale
            binding.textAlertGemmaConfidence.text =
                "Confidence: ${message.gemmaConfidence ?: Constants.CONFIDENCE_LOW}"
        } else {
            binding.cardAlertGemma.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        // Button actions
        binding.btnAlertReport.setOnClickListener {
            dialog.dismiss()
            onReport()
        }

        binding.btnAlertDelete.setOnClickListener {
            dialog.dismiss()
            onDelete()
        }

        binding.btnAlertIgnore.setOnClickListener {
            dialog.dismiss()
            onIgnore()
        }

        dialog.show()
    }
}