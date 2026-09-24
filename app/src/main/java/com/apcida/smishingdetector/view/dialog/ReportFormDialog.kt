package com.apcida.smishingdetector.view.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.apcida.smishingdetector.databinding.DialogReportFormBinding
import com.apcida.smishingdetector.model.entity.Message

class ReportFormDialog(
    private val context: Context,
    private val message: Message,
    private val onReportSubmitted: (String, String?) -> Unit
) {

    fun show() {

        // Inflate your custom XML
        val binding = DialogReportFormBinding.inflate(
            LayoutInflater.from(context)
        )

        // Report options
        val reportTypes = listOf(
            "Phishing Link (Fake Login Page)",
            "Banking / Financial Scam",
            "Prize / Reward Scam",
            "Delivery Scam",
            "Impersonation",
            "Suspicious Link",
            "Other"
        )

        val spinnerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            reportTypes
        )

        spinnerAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        binding.spinnerReportType.adapter = spinnerAdapter


        // IMPORTANT:
        // No setPositiveButton()
        // No setNegativeButton()
        //
        // This removes the old text buttons.
        val dialog = AlertDialog.Builder(context)
            .setTitle("Report Message")
            .setView(binding.root)
            .create()


        // Custom CANCEL button
        binding.btnCancelReport.setOnClickListener {

            dialog.dismiss()
        }


        // Custom SUBMIT button
        binding.btnSubmitReport.setOnClickListener {

            val reportType =
                binding.spinnerReportType
                    .selectedItem
                    ?.toString()
                    ?: "Other"

            val remarksText =
                binding.inputRemarks
                    .text
                    ?.toString()
                    ?.trim()

            val remarks =
                if (remarksText.isNullOrEmpty()) {
                    null
                } else {
                    remarksText
                }

            // Send result back to MessageDetailFragment
            onReportSubmitted(
                reportType,
                remarks
            )

            dialog.dismiss()
        }


        dialog.show()
    }
}