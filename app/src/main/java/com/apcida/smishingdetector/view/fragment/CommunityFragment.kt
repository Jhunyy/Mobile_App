package com.apcida.smishingdetector.view.fragment

import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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
                                 val text: EditText, val category: Spinner)
    private val rows = mutableListOf<ReviewRow>()
    private lateinit var repository: CommunityRepository
    private lateinit var body: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var scamBar: ProgressBar
    private lateinit var safeBar: ProgressBar
    private lateinit var totalBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var candidateContainer: LinearLayout
    private lateinit var startButton: Button
    private lateinit var submitButton: Button
    private lateinit var shareStatus: TextView
    private lateinit var retryButton: Button
    private lateinit var discardButton: Button
    private lateinit var busy: ProgressBar
    private var progress = ContributionProgress(0, 0)
    private var scanning = false

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
            "You may contribute up to 20 scam and 20 safe SMS messages (40 total). ApCiDa will examine accessible messages stored on this device, including messages received before installation. Scanning and Gemma analysis happen on your device. Nothing is shared during the scan. You will review, edit, remove, and confirm each proposed message before upload. Participation is optional.",
            14f, false, R.color.inbox_muted), margin(0, 8))
        intro.addView(label("A contribution may share your edited SMS text, the model's predicted category, your reviewed category, a pseudonymous participant ID, and submission time. Sender numbers and contact names are never included. Researchers must verify labels independently.",
            13f, false, R.color.inbox_muted), margin(0, 10))

        body.addView(label("Your Contribution", 18f, true, R.color.inbox_text), margin(0, 22))
        progressText = label("", 15f, false, R.color.inbox_text)
        val progressPanel = panel()
        progressPanel.addView(progressText)
        scamBar = progressBar(progressPanel, 20)
        safeBar = progressBar(progressPanel, 20)
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
            .setMessage("ApCiDa will examine accessible stored SMS on this device, including older messages, to find possible research contributions. This scan sends no SMS to the server. You will separately review and confirm any upload.")
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
        status.text = "Preparing messages on this device…"
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
                if (progress.scamAccepted >= 20 && progress.safeAccepted >= 20) {
                    status.text = "Contribution Complete. Thank you for helping improve ApCiDa's scam detection."
                    return@launch
                }
                status.text = "Loading the on-device model…"
                val result = CommunityScanner(requireContext().applicationContext).scan(
                    CommunityPolicy.remaining(progress.scamAccepted),
                    CommunityPolicy.remaining(progress.safeAccepted), repository.acceptedIds()
                ) { scanProgress ->
                    withContext(Dispatchers.Main) {
                        status.text = if (scanProgress.analyzing) {
                            "Analyzing stored SMS ${scanProgress.analyzed + 1} on this device… Checked ${scanProgress.examined}; found ${scanProgress.candidates}."
                        } else {
                            "Checked ${scanProgress.examined} stored SMS; analyzed ${scanProgress.analyzed}; found ${scanProgress.candidates} for review…"
                        }
                    }
                }
                status.text = scanResultMessage(result)
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
        val summary = "Checked ${result.progress.examined} stored SMS and analyzed ${result.progress.analyzed}. "
        val outcome = if (result.candidates.isEmpty()) "No messages qualified in this scan." else
            "Found ${result.candidates.size} for review. Edit or remove any message before sharing."
        val limit = when (result.stopReason) {
            CommunityScanResult.StopReason.FINISHED -> ""
            CommunityScanResult.StopReason.TIME_LIMIT -> " Scan stopped after three minutes. Start another scan to check older messages."
            CommunityScanResult.StopReason.MESSAGE_LIMIT -> " Scan stopped after checking 1,000 messages. Start another scan to check older messages."
            CommunityScanResult.StopReason.ANALYSIS_LIMIT -> " Scan stopped after analyzing 20 messages. Start another scan to check older messages."
            CommunityScanResult.StopReason.MODEL_FAILURE ->
                " On-device analysis failed three times in a row. Check that the Gemma model works on this phone."
        }
        return summary + outcome + limit
    }

    private fun renderCandidates(candidates: List<ContributionCandidate>) {
        candidateContainer.addView(label("Review proposed messages", 18f, true, R.color.inbox_text), margin(0, 20))
        candidates.forEach { candidate ->
            val panel = panel(candidateContainer)
            val include = CheckBox(requireContext()).apply { text = "Include this message"; isChecked = true }
            panel.addView(include)
            panel.addView(label("Predicted: ${candidate.predictedCategory}", 13f, false, R.color.inbox_muted))
            val categories = Spinner(requireContext())
            categories.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                listOf("SCAM", "SAFE"))
            categories.setSelection(if (candidate.predictedCategory == "SCAM") 0 else 1)
            panel.addView(categories)
            val text = EditText(requireContext()).apply {
                setText(candidate.text)
                minLines = 3
                maxLines = 8
                textSize = 14f
                hint = "Edit or redact this SMS before sharing"
            }
            panel.addView(text)
            rows += ReviewRow(candidate, include, text, categories)
        }
        submitButton.visibility = View.VISIBLE
        if (!repository.configured()) {
            shareStatus.text = "You can review these messages, but sharing is unavailable in this app build."
            shareStatus.visibility = View.VISIBLE
        }
    }

    private fun confirmSubmission() {
        if (!repository.configured()) {
            val message = "Sharing is unavailable in this app build. A Community API key and research server must be configured before messages can be uploaded. No message has been shared."
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
        val scamRemaining = CommunityPolicy.remaining(progress.scamAccepted)
        val safeRemaining = CommunityPolicy.remaining(progress.safeAccepted)
        val scamCount = selected.count { it.category.selectedItem.toString() == "SCAM" }
        val safeCount = selected.size - scamCount
        if (scamCount > scamRemaining || safeCount > safeRemaining || selected.size > 40 - progress.scamAccepted - progress.safeAccepted) {
            showShareFeedback("Selection exceeds your remaining contribution limits. Remove or relabel messages.")
            return
        }
        val edited = selected.map { row ->
            val text = CommunityPolicy.redact(row.text.text.toString())
            ContributionItem(row.candidate.id, text, row.candidate.predictedCategory,
                row.category.selectedItem.toString(), System.currentTimeMillis())
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
            .setMessage("Send $scamCount Scam and $safeCount Safe edited SMS messages to the research server? The server will also receive predicted and reviewed categories, a pseudonymous ID, and timestamps. Researchers will verify the labels. This is a separate upload confirmation.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm and Share") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { repository.queue(repository.makeBatch(edited)) }
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
                else "Contribution received. Thank you for helping improve ApCiDa."
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
        progressText.text = "Scam Messages: ${progress.scamAccepted}/20\nSafe Messages: ${progress.safeAccepted}/20\nTotal Contributed: ${progress.scamAccepted + progress.safeAccepted}/40"
        scamBar.progress = progress.scamAccepted
        safeBar.progress = progress.safeAccepted
        totalBar.progress = progress.scamAccepted + progress.safeAccepted
        if (progress.scamAccepted == 20 && progress.safeAccepted == 20) {
            startButton.visibility = View.GONE
            status.text = "Contribution Complete. Thank you for helping improve ApCiDa's scam detection."
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
