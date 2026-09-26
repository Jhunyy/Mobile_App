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

        _binding = FragmentMessageDetailBinding.inflate(
            inflater,
            container,
            false
        )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupRepositories()
        setupBackButton()
        setupActionButtons()

        // Get the message ID passed from MessagesFragment
        val messageId = arguments?.getLong("messageId") ?: return

        loadMessage(messageId)
    }

    /**
     * Initialize repositories used by this screen.
     */
    private fun setupRepositories() {

        val database = AppDatabase.getInstance(
            requireContext()
        )

        messageRepository = MessageRepository(
            database.messageDao()
        )

        reportRepository = ReportRepository(
            database.reportDao()
        )

        reportUploadManager = ReportUploadManager(
            requireContext().applicationContext
        )
    }

    /**
     * Back arrow in the Message Details header.
     */
    private fun setupBackButton() {

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    /**
     * Load the selected message from the database.
     */
    private fun loadMessage(messageId: Long) {

        viewLifecycleOwner.lifecycleScope.launch {

            val message =
                messageRepository.getMessageById(messageId)

            message?.let {

                currentMessage = it

                displayMessage(it)
            }
        }
    }

    /**
     * Display message information and risk result.
     */
    private fun displayMessage(message: Message) {

        binding.apply {

            // Message content
            textMessageContent.text = message.content

            // Risk status
            textRiskStatus.text = message.riskLevel

            textRiskScore.text =
                "Risk Score: ${message.riskScore.toInt()}"

            // ---------------------------------
            // Risk card background
            // ---------------------------------

            val backgroundColor =
                when (message.riskLevel) {

                    Constants.RISK_SCAM ->
                        Color.parseColor("#FDECEC")

                    Constants.RISK_SUSPICIOUS ->
                        Color.parseColor("#FFF4E4")

                    else ->
                        Color.parseColor("#EAF6EE")
                }

            cardRiskStatus.setCardBackgroundColor(
                backgroundColor
            )

            // ---------------------------------
            // Risk status text color
            // ---------------------------------

            val statusColor =
                when (message.riskLevel) {

                    Constants.RISK_SCAM ->
                        Color.parseColor("#CA433C")

                    Constants.RISK_SUSPICIOUS ->
                        Color.parseColor("#F2A33A")

                    else ->
                        Color.parseColor("#65B17F")
                }

            textRiskStatus.setTextColor(statusColor)

            // ---------------------------------
            // Gemma AI analysis
            // ---------------------------------

            if (
                message.gemmaInvoked &&
                !message.gemmaRationale.isNullOrEmpty()
            ) {

                cardGemmaAnalysis.visibility =
                    View.VISIBLE

                textGemmaRationale.text =
                    message.gemmaRationale

                textGemmaConfidence.text =
                    "Confidence: ${
                        message.gemmaConfidence ?: "N/A"
                    }"

            } else {

                cardGemmaAnalysis.visibility =
                    View.GONE
            }

            // Report is only available for flagged messages
            btnReport.visibility =
                if (message.isFlagged) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
    }

    /**
     * Set up Report, Delete, and Ignore buttons.
     */
    private fun setupActionButtons() {

        binding.apply {

            // ----------------------------
            // REPORT
            // ----------------------------

            btnReport.setOnClickListener {

                currentMessage?.let { message ->

                    showReportDialog(message)
                }
            }

            // ----------------------------
            // DELETE
            // ----------------------------

            btnDelete.setOnClickListener {

                currentMessage?.let { message ->

                    viewLifecycleOwner.lifecycleScope.launch {

                        messageRepository.deleteMessage(
                            message
                        )

                        Toast.makeText(
                            requireContext(),
                            "Message deleted",
                            Toast.LENGTH_SHORT
                        ).show()

                        findNavController().navigateUp()
                    }
                }
            }

            // ----------------------------
            // IGNORE
            // ----------------------------

            btnIgnore.setOnClickListener {

                findNavController().navigateUp()
            }
        }
    }

    /**
     * Open the custom Report Message dialog.
     */
    private fun showReportDialog(message: Message) {

        val dialog = ReportFormDialog(
            context = requireContext(),
            message = message,

            onReportSubmitted = {
                    reportType,
                    remarks ->

                submitReport(
                    message = message,
                    reportType = reportType,
                    remarks = remarks
                )
            }
        )

        dialog.show()
    }

    /**
     * Save the report locally, then attempt upload.
     */
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
                gemmaClassificationSnapshot =
                    message.gemmaClassification,
                gemmaRationaleSnapshot =
                    message.gemmaRationale,
                isSent = false
            )

            reportRepository.insertReport(report)

            Toast.makeText(
                requireContext(),
                "Report submitted successfully.",
                Toast.LENGTH_SHORT
            ).show()

            // Try sending reports to server.
            // If unavailable, it remains saved locally.
            reportUploadManager.uploadPendingReports()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        _binding = null
    }
}
