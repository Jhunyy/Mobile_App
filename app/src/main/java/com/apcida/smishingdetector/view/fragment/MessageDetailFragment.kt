package com.apcida.smishingdetector.view.fragment

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.network.ReportUploadManager
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.databinding.FragmentMessageDetailBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.entity.Report
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.view.dialog.ReportFormDialog
import kotlinx.coroutines.launch

class MessageDetailFragment : Fragment() {

    private var _binding: FragmentMessageDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageRepository: MessageRepository
    private lateinit var reportRepository: ReportRepository
    private lateinit var reportUploadManager: ReportUploadManager
    private var currentMessage: Message? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessageDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRepositories()

        // Get messageId passed from MessagesFragment
        val messageId = arguments?.getLong("messageId") ?: return
        loadMessage(messageId)

        setupActionButtons()
    }

    private fun setupRepositories() {
        val database = AppDatabase.getInstance(requireContext())
        messageRepository = MessageRepository(database.messageDao())
        reportRepository = ReportRepository(database.reportDao())
        reportUploadManager = ReportUploadManager(requireContext().applicationContext)
    }

    private fun loadMessage(messageId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val message = messageRepository.getMessageById(messageId)
            message?.let {
                currentMessage = it
                displayMessage(it)
            }
        }
    }

    private fun displayMessage(message: Message) {
        binding.apply {

            // Message content
            textMessageContent.text = message.content

            // Risk status
            textRiskStatus.text = message.riskLevel
            textRiskScore.text = "Risk Score: ${message.riskScore.toInt()}"

            // Risk card background color
            val bgColor = when (message.riskLevel) {
                Constants.RISK_SCAM -> Color.parseColor("#FFEBEE")
                Constants.RISK_SUSPICIOUS -> Color.parseColor("#FFF3E0")
                else -> Color.parseColor("#E8F5E9")
            }
            cardRiskStatus.setCardBackgroundColor(bgColor)

            // Risk status text color
            val textColor = when (message.riskLevel) {
                Constants.RISK_SCAM -> Color.parseColor("#B71C1C")
                Constants.RISK_SUSPICIOUS -> Color.parseColor("#E65100")
                else -> Color.parseColor("#2E7D32")
            }
            textRiskStatus.setTextColor(textColor)

            // Gemma analysis — only show if Gemma was invoked
            if (message.gemmaInvoked && !message.gemmaRationale.isNullOrEmpty()) {
                cardGemmaAnalysis.visibility = View.VISIBLE
                textGemmaRationale.text = message.gemmaRationale
                textGemmaConfidence.text = "Confidence: ${message.gemmaConfidence ?: "N/A"}"
            } else {
                cardGemmaAnalysis.visibility = View.GONE
            }

            // Show report button only for flagged messages
            btnReport.visibility = if (message.isFlagged) View.VISIBLE else View.GONE
        }
    }

    private fun setupActionButtons() {
        binding.apply {

            // Report button
            btnReport.setOnClickListener {
                currentMessage?.let { message ->
                    showReportDialog(message)
                }
            }

            // Delete button
            btnDelete.setOnClickListener {
                currentMessage?.let { message ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        messageRepository.deleteMessage(message)
                        findNavController().navigateUp()
                    }
                }
            }

            // Ignore button
            btnIgnore.setOnClickListener {
                findNavController().navigateUp()
            }
        }
    }

    private fun showReportDialog(message: Message) {
        val dialog = ReportFormDialog(
            context = requireContext(),
            message = message,
            onReportSubmitted = { reportType, remarks ->
                submitReport(message, reportType, remarks)
            }
        )
        dialog.show()
    }

    private fun submitReport(
        message: Message,
        reportType: String,
        remarks: String?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val report = Report(
                messageId = message.messageId,
                reportType = reportType,
                remarks = remarks,
                riskScoreSnapshot = message.riskScore,
                gemmaClassificationSnapshot = message.gemmaClassification,
                gemmaRationaleSnapshot = message.gemmaRationale,
                isSent = false
            )
            reportRepository.insertReport(report)
            Toast.makeText(
                requireContext(),
                "Report saved. Upload will run when the server is available.",
                Toast.LENGTH_SHORT
            ).show()
            reportUploadManager.uploadPendingReports()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
