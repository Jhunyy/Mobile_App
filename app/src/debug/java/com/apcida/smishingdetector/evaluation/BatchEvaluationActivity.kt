package com.apcida.smishingdetector.evaluation

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.util.Constants
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Separate debug launcher. No SMS provider reads, message writes, or network calls. */
class BatchEvaluationActivity : AppCompatActivity() {
    private var selectedCsv: Uri? = null
    private var report: EvaluationReport? = null
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var version: EditText
    private lateinit var runButton: Button
    private lateinit var detailsButton: Button
    private lateinit var summaryButton: Button

    private val pickCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedCsv = uri
        report = null
        status.text = if (uri == null) "No CSV selected." else "Selected: ${uri.lastPathSegment ?: "CSV file"}"
        results.text = ""
        detailsButton.isEnabled = false
        summaryButton.isEnabled = false
    }
    private val createDetails = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { export(it, report?.detailsCsv()) } }
    private val createSummary = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { export(it, report?.summaryCsv()) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun addButton(label: String, action: () -> Unit): Button = Button(this).also {
            it.text = label
            it.setOnClickListener { action() }
            container.addView(it)
        }
        container.addView(TextView(this).apply {
            text = "Batch SMS evaluation (debug only)\nLocal CSV → real on-device Gemma → local results"
            textSize = 18f
        })
        version = EditText(this).apply {
            hint = "Detector version or commit ID"
            setSingleLine(true)
            setText("${BuildConfig.VERSION_NAME}-evidence-experiment-v1")
        }
        container.addView(version)
        addButton("Select evaluation CSV") {
            pickCsv.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
        }
        runButton = addButton("Start batch evaluation") { startEvaluation() }
        status = TextView(this).apply { text = "Choose a CSV with verified SAFE/SCAM labels." }
        container.addView(status)
        results = TextView(this)
        container.addView(results)
        detailsButton = addButton("Export evaluation_details_synthetic_200.csv") {
            if (report != null) createDetails.launch("evaluation_details_synthetic_200.csv")
        }.apply { isEnabled = false }
        summaryButton = addButton("Export evaluation_summary_synthetic_200.csv") {
            if (report != null) createSummary.launch("evaluation_summary_synthetic_200.csv")
        }.apply { isEnabled = false }
        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun startEvaluation() {
        val uri = selectedCsv ?: return showError("Select a CSV first.")
        val detectorVersion = version.text.toString().trim()
        if (detectorVersion.isEmpty()) return showError("Enter a detector version or commit ID.")
        runButton.isEnabled = false
        detailsButton.isEnabled = false
        summaryButton.isEnabled = false
        report = null
        results.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                val completed = withContext(Dispatchers.IO) {
                    val input = readLimitedCsv(uri)
                    val rows = EvaluationCsv.parse(input)
                    require(rows.isNotEmpty()) { "CSV has no message rows." }
                    val metadata = EvaluationMetadata(UUID.randomUUID().toString(),
                        detectorVersion, Constants.GEMMA_MODEL_PATH.substringAfterLast('/'),
                        Instant.now().toString())
                    EvaluationRunner.production(this@BatchEvaluationActivity)
                        .run(rows, metadata) { done, total ->
                            runOnUiThread { status.text = "Evaluated $done / $total messages" }
                        }
                }
                report = completed
                val strict = completed.binary(false)
                val warning = completed.binary(true)
                val raw = completed.rawGemmaBinary()
                val experimental = completed.experimentalFinalBinary(false)
                val experimentalWarning = completed.experimentalFinalBinary(true)
                status.text = "Completed ${completed.evaluated.size} / ${completed.records.size} rows; " +
                    "${completed.gemmaFailures} Gemma failures."
                results.text = "Raw Gemma SCAM — accuracy ${percent(raw.accuracy)}, precision ${percent(raw.precision)}, recall ${percent(raw.recall)}, FPR ${percent(raw.falsePositiveRate)}\n\n" +
                    "Production final:\nThree-way exact accuracy: ${percent(if (completed.evaluated.isEmpty()) null else completed.correct.toDouble() / completed.evaluated.size)}\n" +
                    "SUSPICIOUS: ${completed.suspicious}\n\n" +
                    "Strict SCAM only — accuracy ${percent(strict.accuracy)}, precision ${percent(strict.precision)}, recall ${percent(strict.recall)}, F1 ${percent(strict.f1)}\n\n" +
                    "Warning SCAM + SUSPICIOUS — accuracy ${percent(warning.accuracy)}, precision ${percent(warning.precision)}, recall ${percent(warning.recall)}, F1 ${percent(warning.f1)}\n\n" +
                    "Experimental final (paired ${completed.paired.size}):\nStrict SCAM — accuracy ${percent(experimental.accuracy)}, precision ${percent(experimental.precision)}, recall ${percent(experimental.recall)}, FPR ${percent(experimental.falsePositiveRate)}\n" +
                    "Warnings included — recall ${percent(experimentalWarning.recall)}, FPR ${percent(experimentalWarning.falsePositiveRate)}; SUSPICIOUS ${completed.paired.count { it.experimentalFinalVerdict == com.apcida.smishingdetector.model.data.RiskLevel.SUSPICIOUS }}\n\n" +
                    "Gemma average/median (successful only): ${completed.averageInferenceMs ?: "N/A"} / ${completed.medianInferenceMs ?: "N/A"} ms"
                detailsButton.isEnabled = true
                summaryButton.isEnabled = true
            } catch (error: Exception) {
                showError(error.message ?: "Evaluation failed.")
                status.text = "Evaluation stopped. No report was exported."
            } finally {
                runButton.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun readLimitedCsv(uri: Uri): String {
        val limit = 10 * 1024 * 1024
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= limit) { "CSV exceeds 10 MB." }
                output.write(buffer, 0, read)
            }
        } ?: error("Unable to open selected CSV.")
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun export(uri: Uri, content: String?) {
        if (content == null) return showError("Run an evaluation before exporting.")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(content)
                } ?: error("Unable to create export file.")
                runOnUiThread { Toast.makeText(this@BatchEvaluationActivity,
                    "Evaluation CSV exported.", Toast.LENGTH_SHORT).show() }
            } catch (error: Exception) {
                runOnUiThread { showError(error.message ?: "Export failed.") }
            }
        }
    }

    private fun percent(value: Double?): String =
        value?.let { "%.1f%%".format(java.util.Locale.US, it * 100) } ?: "N/A"

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
