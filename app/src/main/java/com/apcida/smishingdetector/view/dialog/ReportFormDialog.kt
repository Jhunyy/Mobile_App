package com.apcida.smishingdetector.view.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.apcida.smishingdetector.databinding.DialogReportFormBinding
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.Constants

class ReportFormDialog(
    private val context: Context,
    private val message: Message,
    private val onReportSubmitted: (String, String?, Boolean) -> Unit
) {

    fun show() {

        // Inflate your custom XML
        val binding = DialogReportFormBinding.inflate(
            LayoutInflater.from(context)
        )

        val reportTypes = listOf(
            Constants.REPORT_NOT_SCANNED,
            Constants.REPORT_ANALYSIS_INCOMPLETE,
            Constants.REPORT_MISSED_SCAM,
            Constants.REPORT_FALSE_ALERT,
            Constants.REPORT_OTHER
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
        val suggestedType = when {
            message.processingState == ProcessingState.AI_FAILED.name -> Constants.REPORT_ANALYSIS_INCOMPLETE
            message.processingState != ProcessingState.COMPLETED.name -> Constants.REPORT_OTHER
            message.isFlagged -> Constants.REPORT_FALSE_ALERT
            else -> Constants.REPORT_MISSED_SCAM
        }
        binding.spinnerReportType.setSelection(reportTypes.indexOf(suggestedType))

        val canShareSms = message.content.isNotBlank() && message.content.length <= 4000
        binding.checkboxShareSms.isEnabled = canShareSms
        binding.textSmsPreview.text = message.content
        binding.textSmsPreview.visibility = View.VISIBLE
        binding.btnSubmitReport.isEnabled = false
        binding.checkboxShareSms.setOnCheckedChangeListener { _, checked ->
            binding.btnSubmitReport.isEnabled = checked && canShareSms
        }
        if (!canShareSms) {
            binding.checkboxShareSms.text = "This SMS is empty or too long to report (maximum 4,000 characters)."
        }


        // IMPORTANT:
        // No setPositiveButton()
        // No setNegativeButton()
        //
        // This removes the old text buttons.
        val dialog = AlertDialog.Builder(context)
            .setTitle("Report a scan issue")
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
                    ?: Constants.REPORT_OTHER

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
            if (!binding.checkboxShareSms.isChecked || !canShareSms) return@setOnClickListener
            onReportSubmitted(reportType, remarks, binding.checkboxShareSms.isChecked)

            dialog.dismiss()
        }


        dialog.show()
    }
}
