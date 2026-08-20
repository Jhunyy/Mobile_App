package com.apcida.smishingdetector.view.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apcida.smishingdetector.databinding.ItemReportLogBinding
import com.apcida.smishingdetector.model.entity.Report
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportLogAdapter : ListAdapter<Report, ReportLogAdapter.ReportViewHolder>(
    ReportDiffCallback()
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemReportLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ReportViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ReportViewHolder(
        private val binding: ItemReportLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(report: Report) {
            binding.apply {

                // Report type
                textReportType.text = report.reportType

                // Sent status
                if (report.isSent) {
                    textReportStatus.text = "Sent ✓"
                    textReportStatus.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    textReportStatus.text = "Pending"
                    textReportStatus.setTextColor(Color.parseColor("#E65100"))
                }

                // Report time
                val sdf = SimpleDateFormat(
                    "MMM dd, yyyy HH:mm",
                    Locale.getDefault()
                )
                textReportTime.text = sdf.format(Date(report.reportedAt))

                // Remarks — only show if present
                if (!report.remarks.isNullOrEmpty()) {
                    textReportRemarks.visibility = View.VISIBLE
                    textReportRemarks.text = report.remarks
                } else {
                    textReportRemarks.visibility = View.GONE
                }
            }
        }
    }

    class ReportDiffCallback : DiffUtil.ItemCallback<Report>() {
        override fun areItemsTheSame(oldItem: Report, newItem: Report): Boolean {
            return oldItem.reportId == newItem.reportId
        }

        override fun areContentsTheSame(oldItem: Report, newItem: Report): Boolean {
            return oldItem == newItem
        }
    }
}