package com.apcida.smishingdetector.view.fragment

import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.backend.community.CommunityPolicy
import com.apcida.smishingdetector.backend.community.CommunityRepository
import com.apcida.smishingdetector.backend.community.CommunityScanner
import com.apcida.smishingdetector.backend.community.CommunityScanResult
import com.apcida.smishingdetector.backend.community.ContributionCandidate
import com.apcida.smishingdetector.backend.community.ContributionItem
import com.apcida.smishingdetector.backend.community.ContributionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CommunityFragment : Fragment() {
    private data class ReviewRow(val candidate: ContributionCandidate, val include: CheckBox,
                                 val text: EditText)
    private val rows = mutableListOf<ReviewRow>()
    private lateinit var repository: CommunityRepository
    private lateinit var body: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var totalBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var candidateContainer: LinearLayout
    private lateinit var startButton: Button
    private lateinit var submitButton: Button
    private lateinit var shareStatus: TextView
    private lateinit var retryButton: Button
    private lateinit var discardButton: Button
    private lateinit var busy: ProgressBar
    private var progress = ContributionProgress(0)
    private var scanning = false
    private var lastScanExamined = 0
    private var lastScanOffered = 0

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScan() else status.text = "SMS access was denied. You can return to Settings at any time."
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        repository = CommunityRepository(requireContext().applicationContext)
        progress = repository.cachedProgress()
        val scroll = ScrollView(requireContext()).apply { setBackgroundColor(Color.WHITE) }
        body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
        }
        scroll.addView(body)

        val back = label("‹   Help the Community", 25f, true, R.color.inbox_text)
        back.setOnClickListener { findNavController().navigateUp() }
        body.addView(back)
        body.addView(label("Help improve and evaluate SMS scam detection research.", 15f, false, R.color.inbox_muted), margin(0, 8))
        val intro = panel()
        intro.addView(label("Your choice", 18f, true, R.color.inbox_text))
        intro.addView(label(
            "You may contribute up to 40 SMS messages over several sessions. deText will examine accessible SMS received in the last 30 days, including messages received before installation. Local keyword rules are recorded for research; they do not decide which eligible messages are offered or verify a label. Nothing is shared during the scan. Review, edit, and select each message before upload. Participation is optional.",
            14f, false, R.color.inbox_muted), margin(0, 8))
        intro.addView(label("A contribution shares your edited SMS text, a local rule suggestion, a pseudonymous participant ID, and review/submission times. Sender numbers and contact names are not sent. A network expert will independently label the messages; research analysis happens later.",
            13f, false, R.color.inbox_muted), margin(0, 10))
        intro.addView(label("Study records are kept in the active research database for up to 365 days after your latest upload. You can withdraw your contributions in this screen.",
            13f, false, R.color.inbox_muted), margin(0, 10))

        body.addView(label("Your Contribution", 18f, true, R.color.inbox_text), margin(0, 22))
        progressText = label("", 15f, false, R.color.inbox_text)
        val progressPanel = panel()
        progressPanel.addView(progressText)
        totalBar = progressBar(progressPanel, 40)

        startButton = button("Start Contributing") { askScanConsent() }
        retryButton = button("Retry Approved Submission") { submitPending() }
        retryButton.visibility = if (repository.hasPending()) View.VISIBLE else View.GONE
        discardButton = button("Discard Pending Submission") {
            AlertDialog.Builder(requireContext())
                .setTitle("Discard approved messages?")
                .setMessage("This removes messages waiting on this device. It cannot undo messages already accepted by the server.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Discard") { _, _ ->
                    repository.discardPending()
                    retryButton.visibility = View.GONE
                    discardButton.visibility = View.GONE
                    status.text = "Pending messages discarded."
                }.show()
        }
        discardButton.visibility = retryButton.visibility
        button("Withdraw Study Contributions") {
            AlertDialog.Builder(requireContext())
                .setTitle("Withdraw contributed messages?")
                .setMessage("This requests deletion of your study contributions from the research server and clears pending messages on this phone. It does not revoke AI Protection enrollment.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Withdraw") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            repository.withdraw()
                            progress = ContributionProgress(0)
                            showProgress()
                            retryButton.visibility = View.GONE
                            discardButton.visibility = View.GONE
                            showShareFeedback("Study contributions withdrawn.")
                        } catch (error: Exception) {
                            showShareFeedback(error.message ?: "Withdrawal unavailable. Try again later.")
                        }
                    }
                }.show()
        }
        status = label("", 14f, false, R.color.inbox_muted)
        body.addView(status, margin(0, 10))
        busy = ProgressBar(requireContext()).apply { visibility = View.GONE }
        body.addView(busy, margin(0, 10))
        candidateContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        body.addView(candidateContainer)
        submitButton = button("Review and Confirm Sharing") { confirmSubmission() }
        submitButton.visibility = View.GONE
        shareStatus = label("", 14f, false, R.color.inbox_muted).apply { visibility = View.GONE }
        body.addView(shareStatus, margin(8, 0))
        button("Cancel") { findNavController().navigateUp() }
        showProgress()
        return scroll
    }

    override fun onResume() {
        super.onResume()
        if (::progressText.isInitialized) {
            lifecycleScope.launch {
                try {
                    val refreshed = repository.refresh()
                    if (!scanning) {
                        progress = refreshed
                        showProgress()
                    }
                } catch (_: Exception) {
                    if (!scanning) status.text = "Saved progress shown. Server progress is temporarily unavailable."
                }
            }
        }
    }

    private fun askScanConsent() {
        if (repository.hasPending()) {
            status.text = "Retry or resolve your previous approved submission before scanning again."
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Start local SMS scan?")
            .setMessage("deText will examine accessible SMS received in the last 30 days on this device to find possible research contributions. This scan sends no SMS to the server. You will separately review and confirm any upload.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start Contributing") { _, _ ->
                if (com.apcida.smishingdetector.util.PermissionHelper.hasReadSmsPermission(requireContext())) startScan()
                else smsPermission.launch(Manifest.permission.READ_SMS)
            }.show()
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        startButton.isEnabled = false
        busy.visibility = View.VISIBLE
        status.text = "Preparing SMS from the last 30 days…"
        candidateContainer.removeAllViews()
        rows.clear()
        submitButton.visibility = View.GONE
        shareStatus.visibility = View.GONE
        lifecycleScope.launch {
            try {
                progress = withTimeoutOrNull(10_000L) {
                    try {
                        repository.refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } ?: repository.cachedProgress()
                showProgress()
                if (progress.totalAccepted >= 40) {
                    status.text = "Contribution Complete. Thank you for helping improve deText's scam detection."
                    return@launch
                }
                status.text = "Screening recent SMS with local keyword rules…"
                val result = CommunityScanner(requireContext().applicationContext).scan(
                    CommunityPolicy.remaining(progress.totalAccepted), repository.acceptedIds()
                ) { scanProgress ->
                    withContext(Dispatchers.Main) {
                        status.text = if (scanProgress.analyzing) {
                            "Screening recent SMS ${scanProgress.analyzed + 1} on this device… Checked ${scanProgress.examined}; found ${scanProgress.candidates}."
                        } else {
                            "Checked ${scanProgress.examined} SMS from the last 30 days; screened ${scanProgress.analyzed} locally; found ${scanProgress.candidates} for review…"
                        }
                    }
                }
                status.text = scanResultMessage(result)
                lastScanExamined = result.progress.examined
                lastScanOffered = result.candidates.size
                if (result.candidates.isNotEmpty()) renderCandidates(result.candidates)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = error.message ?: "The local scan could not be completed."
            } finally {
                scanning = false
                startButton.isEnabled = true
                busy.visibility = View.GONE
            }
        }
    }

    private fun scanResultMessage(result: CommunityScanResult): String {
        val summary = "Checked ${result.progress.examined} SMS from the last 30 days and screened ${result.progress.analyzed} locally. "
        val outcome = if (result.candidates.isEmpty()) "No messages qualified in this scan." else
            "Found ${result.candidates.size} for review. Edit or remove any message before sharing."
        val limit = when (result.stopReason) {
            CommunityScanResult.StopReason.FINISHED -> ""
            CommunityScanResult.StopReason.TIME_LIMIT -> " Scan stopped after three minutes."
            CommunityScanResult.StopReason.MESSAGE_LIMIT -> " Scan stopped after checking 1,000 messages."
            CommunityScanResult.StopReason.ANALYSIS_LIMIT -> " Scan stopped after offering 20 messages."
            CommunityScanResult.StopReason.MODEL_FAILURE ->
                " Screening stopped after repeated failures."
        }
        val resume = if (result.willResume) {
            " Start another scan to continue with older SMS from the last 30 days."
        } else ""
        return summary + outcome + limit + resume
    }

    private fun renderCandidates(candidates: List<ContributionCandidate>) {
        candidateContainer.addView(label("Review proposed messages", 18f, true, R.color.inbox_text), margin(0, 20))
        candidates.forEach { candidate ->
            val panel = panel(candidateContainer)
            val include = CheckBox(requireContext()).apply {
                text = "Include this message"
                isChecked = false
                setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
            }
            panel.addView(include)
            val text = EditText(requireContext()).apply {
                setText(candidate.text)
                minLines = 3
                maxLines = 8
                textSize = 14f
                hint = "Edit or redact this SMS before sharing"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_text))
                setHintTextColor(ContextCompat.getColor(requireContext(), R.color.inbox_muted))
            }
            panel.addView(text)
            rows += ReviewRow(candidate, include, text)
        }
        submitButton.visibility = View.VISIBLE
        if (!repository.configured()) {
            shareStatus.text = "You can review these messages, but sharing is unavailable in this app build."
            shareStatus.visibility = View.VISIBLE
        }
    }

    private fun confirmSubmission() {
        if (!repository.configured()) {
            val message = "Sharing is unavailable until this phone is enrolled and the research server is enabled. No message has been shared."
            showShareFeedback(message)
            AlertDialog.Builder(requireContext())
                .setTitle("Sharing unavailable")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (repository.hasPending()) {
            showShareFeedback("A previous approved submission is pending. Retry it first.")
            return
        }
        val selected = rows.filter { it.include.isChecked }
        if (selected.isEmpty()) { showShareFeedback("Select at least one message to share."); return }
        if (selected.size > CommunityPolicy.remaining(progress.totalAccepted)) {
            showShareFeedback("Selection exceeds your remaining 40-message limit. Remove messages.")
            return
        }
        val edited = selected.map { row ->
            val text = CommunityPolicy.redact(row.text.text.toString())
            ContributionItem(row.candidate.id, text, row.candidate.localSuggestion,
                System.currentTimeMillis())
        }
        if (edited.any { !CommunityPolicy.eligible(it.text) }) {
            showShareFeedback("A selected message is empty, too long, or contains a code or sensitive term. Edit or remove it.")
            return
        }
        if (edited.map { CommunityPolicy.fingerprint(it.text) }.distinct().size != edited.size) {
            showShareFeedback("Remove duplicate edited messages before sharing.")
            return
        }
        shareStatus.visibility = View.GONE
        AlertDialog.Builder(requireContext())
            .setTitle("Share reviewed messages?")
            .setMessage("Send ${selected.size} edited SMS messages to the research server for thesis evaluation only? It will also receive local suggestions, a pseudonymous ID, and timestamps. A network expert will label them independently. Active study records are kept for up to 365 days after your latest upload; you can withdraw them. This is a separate upload confirmation.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm and Share") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.queue(repository.makeBatch(
                            edited, lastScanExamined, lastScanOffered)) }
                        candidateContainer.removeAllViews()
                        rows.clear()
                        submitButton.visibility = View.GONE
                        retryButton.visibility = View.VISIBLE
                        discardButton.visibility = View.VISIBLE
                        submitPending()
                    } catch (error: Exception) {
                        showShareFeedback(error.message ?: "Unable to save approved submission.")
                    }
                }
            }.show()
    }

    private fun submitPending() {
        retryButton.isEnabled = false
        discardButton.isEnabled = false
        busy.visibility = View.VISIBLE
        showShareFeedback("Sending approved messages…")
        lifecycleScope.launch {
            try {
                progress = repository.submitPending()
                showProgress()
                retryButton.visibility = if (repository.hasPending()) View.VISIBLE else View.GONE
                discardButton.visibility = retryButton.visibility
                showShareFeedback(if (repository.hasPending()) "Some approved messages remain pending. Retry later."
                else "Contribution received. Thank you for helping improve deText."
                )
            } catch (error: Exception) {
                showShareFeedback((error.message ?: "Submission unavailable") + " Approved messages remain pending for retry.")
                retryButton.visibility = View.VISIBLE
                discardButton.visibility = View.VISIBLE
            } finally {
                retryButton.isEnabled = true
                discardButton.isEnabled = true
                busy.visibility = View.GONE
            }
        }
    }

    private fun showShareFeedback(message: String) {
        status.text = message
        shareStatus.text = message
        shareStatus.visibility = View.VISIBLE
    }

    private fun showProgress() {
        progressText.text = "Total Contributed: ${progress.totalAccepted}/40"
        totalBar.progress = progress.totalAccepted
        if (progress.totalAccepted >= 40) {
            startButton.visibility = View.GONE
            status.text = "Contribution Complete. Thank you for helping improve deText's scam detection."
        }
    }

    private fun progressBar(parent: LinearLayout, maximum: Int): ProgressBar =
        ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = maximum
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF00A86B.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE3F1EA.toInt())
            parent.addView(this, margin(8, 0))
        }

    private fun panel(parent: LinearLayout = body): LinearLayout {
        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFFE2E6EA.toInt())
            }
        }
        parent.addView(panel, margin(12, 12))
        return panel
    }

    private fun button(text: String, action: () -> Unit): Button {
        val button = Button(requireContext()).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (text == "Cancel") 0xFF626B79.toInt() else Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (text == "Cancel") 0xFFF1F3F5.toInt() else 0xFF00A86B.toInt())
            setOnClickListener { action() }
        }
        body.addView(button, margin(14, 0))
        return button
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(requireContext(), color))
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun margin(top: Int, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
