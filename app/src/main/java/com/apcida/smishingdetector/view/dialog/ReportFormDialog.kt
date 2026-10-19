package com.apcida.smishingdetector.view.dialog

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.util.Constants

class ReportFormDialog(
    private val context: Context,
    private val message: Message,
    private val onReportSubmitted: (reportType: String, remarks: String?) -> Unit
) {

    private val reportTypes = listOf(
        Constants.REPORT_NOT_SCANNED,
        Constants.REPORT_MISSED_SCAM,
        Constants.REPORT_FALSE_ALERT,
        Constants.REPORT_OTHER
    )

    fun show() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_report_form, null)

        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_report_type)
        val remarksInput = dialogView.findViewById<EditText>(R.id.input_remarks)

        // Suggest a reason based on the saved result; the user may change it.
        val spinnerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            reportTypes
        )
        spinnerAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )
        spinner.adapter = spinnerAdapter
        val suggestedType = when {
            message.processingState != ProcessingState.COMPLETED.name -> Constants.REPORT_NOT_SCANNED
            message.isFlagged -> Constants.REPORT_FALSE_ALERT
            else -> Constants.REPORT_MISSED_SCAM
        }
        spinner.setSelection(reportTypes.indexOf(suggestedType))

        AlertDialog.Builder(context)
            .setTitle("Report a scan issue")
            .setView(dialogView)
            .setPositiveButton("Submit Report") { _, _ ->
                val selectedType = spinner.selectedItem.toString()
                val remarks = remarksInput.text.toString()
                    .trim()
                    .ifEmpty { null }
                onReportSubmitted(selectedType, remarks)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
