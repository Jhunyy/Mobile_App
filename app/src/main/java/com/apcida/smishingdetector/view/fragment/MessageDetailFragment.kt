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
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.databinding.FragmentMessageDetailBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.view.presentation.DetectionPresentation
import com.apcida.smishingdetector.util.ContactNameResolver
import com.apcida.smishingdetector.view.dialog.ReportFormDialog
import com.apcida.smishingdetector.view.widget.PullToRefreshLayout
import kotlinx.coroutines.CancellationException
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
    private lateinit var pullToRefresh: PullToRefreshLayout

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
        pullToRefresh = PullToRefreshLayout(requireContext()).apply { setContent(binding.root) }
        return pullToRefresh
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
        setupPullToRefresh(messageId)
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

    private fun setupPullToRefresh(messageId: Long) {
        pullToRefresh.canChildScrollUp = { binding.root.canScrollVertically(-1) }
        pullToRefresh.setOnRefreshListener {
            val layout = pullToRefresh
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val message = messageRepository.getMessageById(messageId)
                    if (message == null) {
                        Toast.makeText(requireContext(), "Message is no longer available.", Toast.LENGTH_SHORT).show()
                        findNavController().navigateUp()
                    } else {
                        currentMessage = message
                        displayMessage(message)
                        refreshSenderName(force = true)
                        Toast.makeText(requireContext(), "Message details refreshed.", Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(requireContext(), "Could not refresh message details.", Toast.LENGTH_SHORT).show()
                } finally {
                    layout.isRefreshing = false
                }
            }
        }
    }

    /**
     * Display message information and risk result.
     */
    private fun displayMessage(message: Message) {

        binding.apply {
            val presentation = DetectionPresentation.forMessage(message)

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

            textRiskStatus.text = presentation.badge

            if (message.combinedRiskScore == null) {
                textRiskScore.text = "Rule risk score: ${message.riskScore.toInt()}"
                textRiskBreakdown.visibility = View.GONE
            } else {
                textRiskScore.text = "Local rule score: ${message.riskScore.toInt()}"
                val reasons = buildList {
                    if (message.ruleRiskPoints > 0) add("Message signals +${message.ruleRiskPoints}")
                    if (message.aiRiskPoints > 0) add("Gemma +${message.aiRiskPoints}")
                    if (message.historyRiskPoints > 0) add("Recent sender history +${message.historyRiskPoints}")
                    if (message.senderRiskPoints > 0) add("Sender mismatch +${message.senderRiskPoints}")
                }
                textRiskBreakdown.text = if (reasons.isEmpty()) {
                    "No scored risk signals found. This is not a guarantee of safety."
                } else reasons.joinToString(" · ")
                textRiskBreakdown.visibility = View.VISIBLE
            }

            // ---------------------------------
            // Risk badge and sender avatar
            // ---------------------------------

            val (statusColor, badgeColor) = when (presentation.tone) {
                DetectionPresentation.Tone.SCAM ->
                    Color.parseColor("#CA433C") to Color.parseColor("#FDECEC")
                DetectionPresentation.Tone.SUSPICIOUS ->
                    Color.parseColor("#A86508") to Color.parseColor("#FFF1D8")
                DetectionPresentation.Tone.UNVERIFIED ->
                    Color.parseColor("#346783") to Color.parseColor("#E8F0F6")
                DetectionPresentation.Tone.SAFE ->
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

            textRecentSenderHistory.visibility =
                if (message.historyRiskPoints == 25) View.VISIBLE else View.GONE
            cardGemmaAnalysis.visibility = View.VISIBLE
            btnRetryAi.visibility = View.GONE
            btnToggleAnalysisDetails.visibility = View.GONE
            layoutAnalysisDetails.visibility = if (presentation.aiVerified) View.VISIBLE else View.GONE
            val olderExplanation = if (message.verdictSource == "LEGACY_ON_DEVICE" &&
                !message.gemmaRationale.isNullOrBlank()) {
                " Earlier app reason: ${message.gemmaRationale}"
            } else ""
            textGemmaRationale.text = presentation.explanation + olderExplanation
            textGemmaClassification.text = "Remote AI result: ${presentation.verdict}"
            textGemmaConfidence.text = "Model confidence: ${presentation.confidence ?: "N/A"} (self-reported)"
            textGemmaRawReason.visibility = View.GONE

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
                "Report saved on this device. Sharing is unavailable in this build.",
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
