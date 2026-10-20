package com.apcida.smishingdetector.view.fragment

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.reporting.ReportSnapshotFactory
import com.apcida.smishingdetector.backend.worker.ReportUploadWorker
import com.apcida.smishingdetector.backend.worker.SmsProcessingWorker
import com.apcida.smishingdetector.controller.SmsController
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.databinding.FragmentMessageDetailBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.data.AiAnalysisStatus
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.util.ContactNameResolver
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.view.dialog.ReportFormDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageDetailFragment : Fragment() {

    private var _binding: FragmentMessageDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageRepository: MessageRepository
    private lateinit var reportRepository: ReportRepository

    private var currentMessage: Message? = null
    private var senderName: String? = null
    private var senderLookupJob: Job? = null
    private var lastResolvedAddress: String? = null
    private var lastNamesEnabled = false
    private var observingContacts = false
    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshSenderName(force = true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            if (ContactNameResolver.isEnabled(requireContext()) && !observingContacts) {
                requireContext().contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, contactsObserver
                )
                observingContacts = true
            }
            refreshSenderName(force = true)
        }
    }

    override fun onPause() {
        senderLookupJob?.cancel()
        if (observingContacts) {
            requireContext().contentResolver.unregisterContentObserver(contactsObserver)
            observingContacts = false
        }
        super.onPause()
    }

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

        observeMessage(messageId)
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
     * Keep the details current while Gemma updates the saved message.
     */
    private fun observeMessage(messageId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            messageRepository.observeMessageById(messageId).collectLatest { message ->
                if (message == null) return@collectLatest
                if (message.senderAddress != lastResolvedAddress) senderName = null
                currentMessage = message
                displayMessage(message)
                refreshSenderName()
            }
        }
    }

    /**
     * Display message information and risk result.
     */
    private fun displayMessage(message: Message) {

        binding.apply {
            val analysisPending = message.processingState in setOf(
                ProcessingState.PENDING.name,
                ProcessingState.RULE_ANALYZED.name,
                ProcessingState.AI_QUEUED.name,
                ProcessingState.AI_ANALYZING.name
            )

            showSender(message)
            textReceivedAt.text = SimpleDateFormat(
                "MMM d, yyyy • h:mm a", Locale.getDefault()
            ).format(Date(message.receivedAt))

            // ---------------------------------
            // Message content
            // ---------------------------------

            textMessageContent.text = message.content

            // ---------------------------------
            // Risk status
            // ---------------------------------

            textRiskStatus.text = when {
                analysisPending && message.processingState == ProcessingState.PENDING.name ->
                    "ANALYZING"
                message.finalClassification == Constants.RISK_SCAM -> "SCAM"
                message.finalClassification == Constants.RISK_SUSPICIOUS -> "SUSPICIOUS"
                else -> "SAFE"
            }

            textRiskScore.text =
                "Risk score: ${message.riskScore.toInt()}"

            // ---------------------------------
            // Risk badge and sender avatar
            // ---------------------------------

            val (statusColor, badgeColor) = when {
                analysisPending && message.processingState == ProcessingState.PENDING.name ->
                    Color.parseColor("#346783") to Color.parseColor("#E8F0F6")
                message.finalClassification == Constants.RISK_SCAM ->
                    Color.parseColor("#CA433C") to Color.parseColor("#FDECEC")
                message.finalClassification == Constants.RISK_SUSPICIOUS ->
                    Color.parseColor("#A86508") to Color.parseColor("#FFF1D8")
                else ->
                    Color.parseColor("#218957") to Color.parseColor("#E5F4E9")
            }

            textRiskStatus.background = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = 18f * resources.displayMetrics.density
            }
            textRiskStatus.setTextColor(statusColor)
            cardSenderAvatar.setCardBackgroundColor(statusColor)

            // ---------------------------------
            // Gemma AI Analysis
            // ---------------------------------

            if (analysisPending) {
                btnRetryAi.visibility = View.GONE
                cardGemmaAnalysis.visibility = View.VISIBLE
                textGemmaRationale.text = "Analyzing this message on your device…"
                textGemmaConfidence.visibility = View.GONE
            } else if (message.aiAnalysisStatus == AiAnalysisStatus.UNAVAILABLE.name) {
                btnRetryAi.visibility = View.VISIBLE
                btnRetryAi.setOnClickListener {
                    btnRetryAi.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        val context = requireContext().applicationContext
                        if (SmsController(context).queueAiRetry(message.messageId)) {
                            SmsProcessingWorker.enqueue(context, message.messageId)
                        } else {
                            btnRetryAi.isEnabled = true
                        }
                    }
                }
                btnRetryAi.isEnabled = true
                cardGemmaAnalysis.visibility = View.VISIBLE
                textGemmaRationale.text = message.gemmaRationale
                    ?: "AI analysis is unavailable; the rule result was preserved."
                textGemmaConfidence.visibility = View.VISIBLE
                textGemmaConfidence.text = "Status: AI unavailable"
            } else if (message.gemmaInvoked) {
                btnRetryAi.visibility = View.GONE

                // Gemma was attempted, so show the card
                cardGemmaAnalysis.visibility = View.VISIBLE
                textGemmaConfidence.visibility = View.VISIBLE

                if (message.gemmaClassification != Constants.GEMMA_UNCERTAIN &&
                    !message.gemmaRationale.isNullOrEmpty()) {

                    // Gemma successfully returned an explanation
                    textGemmaRationale.text = when {
                        message.gemmaClassification == Constants.GEMMA_SCAM &&
                            message.finalClassification != Constants.RISK_SCAM ->
                            "The AI suggested scam, but the available evidence did not justify a scam alert. ${message.gemmaRationale}"
                        message.finalClassification == Constants.RISK_SUSPICIOUS &&
                            message.gemmaClassification == Constants.GEMMA_LEGITIMATE ->
                            "Keyword indicators remain; verify independently. ${message.gemmaRationale}"
                        else -> message.gemmaRationale
                    }

                    textGemmaConfidence.text =
                        "Confidence: ${message.gemmaConfidence ?: "N/A"}"

                } else {

                    // Gemma was invoked but inference was unavailable
                    textGemmaRationale.text = if (message.gemmaConfidence == null) {
                        "Contextual analysis was unavailable. Classification based on keyword scoring only."
                    } else {
                        message.gemmaRationale ?: "Contextual analysis was inconclusive."
                    }

                    textGemmaConfidence.text =
                        "Confidence: ${message.gemmaConfidence ?: "LOW"}"
                }

            } else {
                btnRetryAi.visibility = View.GONE

                // Gemma was never invoked for this message
                cardGemmaAnalysis.visibility = View.GONE
            }

            // ---------------------------------
            // Report button
            // ---------------------------------

            btnReport.visibility = View.VISIBLE
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
                    remarks,
                    includeSmsText ->

                submitReport(
                    message = message,
                    reportType = reportType,
                    remarks = remarks,
                    includeSmsText = includeSmsText
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
        remarks: String?,
        includeSmsText: Boolean
    ) {

        val reportedMessage = currentMessage ?: message

        viewLifecycleOwner.lifecycleScope.launch {

            val report = ReportSnapshotFactory.create(
                message = reportedMessage,
                reportType = reportType,
                remarks = remarks,
                includeSmsText = includeSmsText,
                appVersion = BuildConfig.VERSION_NAME
            )

            reportRepository.insertReport(report)
            ReportUploadWorker.enqueue(requireContext().applicationContext)

            Toast.makeText(
                requireContext(),
                "Report saved. Upload will run when the server is available.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showSender(message: Message) {
        if (_binding == null) return
        binding.textSenderValue.text = senderName ?: message.senderAddress
            ?: getString(R.string.unknown_sender)
        binding.textSenderAddress.text = if (senderName != null) message.senderAddress else null
        binding.textSenderAddress.visibility = if (senderName != null) View.VISIBLE else View.GONE
    }

    private fun refreshSenderName(force: Boolean = false) {
        if (_binding == null) return
        val message = currentMessage ?: return
        val enabled = ContactNameResolver.isEnabled(requireContext())
        if (!force && message.senderAddress == lastResolvedAddress && enabled == lastNamesEnabled) {
            return
        }

        lastResolvedAddress = message.senderAddress
        lastNamesEnabled = enabled
        senderLookupJob?.cancel()
        senderName = null
        showSender(message)
        val address = message.senderAddress ?: return
        if (!enabled) return

        val context = requireContext().applicationContext
        senderLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            senderName = ContactNameResolver.findName(context, address)
            if (currentMessage?.senderAddress == address) showSender(message)
        }
    }

    override fun onDestroyView() {
        senderLookupJob?.cancel()
        super.onDestroyView()

        _binding = null
    }
}
