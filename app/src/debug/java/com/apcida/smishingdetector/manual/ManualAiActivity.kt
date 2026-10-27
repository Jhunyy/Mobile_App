package com.apcida.smishingdetector.manual

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Separate debug launcher: it never reads the inbox or invokes the local SMS processing path. */
class ManualAiActivity : AppCompatActivity() {
    private val client = ManualAiClient()
    private lateinit var serverAddress: EditText
    private lateinit var apiKey: EditText
    private lateinit var message: EditText
    private lateinit var syntheticOnly: CheckBox
    private lateinit var analyzeButton: Button
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            textSize = 17f
            setPadding(0, spacing / 2, 0, spacing / 4)
        }
        body.addView(label("Remote AI server · experimental manual test"))
        body.addView(label("Enter synthetic SMS text only. This screen does not read your inbox."))
        body.addView(label("Mac server address"))
        serverAddress = EditText(this).apply {
            hint = "http://192.168.1.23:3000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        body.addView(serverAddress)
        body.addView(label("Development API key (memory only)"))
        apiKey = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        body.addView(apiKey)
        body.addView(label("Synthetic SMS message"))
        message = EditText(this).apply {
            hint = "Paste a synthetic test message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
        }
        body.addView(message)
        syntheticOnly = CheckBox(this).apply { text = "I confirm this is a synthetic test message" }
        body.addView(syntheticOnly)
        analyzeButton = Button(this).apply {
            text = "Analyze on remote AI server"
            setOnClickListener { analyzeManually() }
        }
        body.addView(analyzeButton)
        result = label("No remote analysis yet.").apply { id = View.generateViewId() }
        body.addView(result)
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private fun analyzeManually() {
        if (!syntheticOnly.isChecked) {
            result.text = "Confirm that the message is synthetic before sending it."
            return
        }
        val url = serverAddress.text.toString()
        val key = apiKey.text.toString()
        val text = message.text.toString()
        val id = UUID.randomUUID().toString()
        val receivedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
        analyzeButton.isEnabled = false
        result.text = "Contacting remote AI server…"
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { client.analyze(url, key, text, id, receivedAt) }
            result.text = when (outcome) {
                is ManualAiResult.Success -> "Source: remote AI server (experimental)\n" +
                    "Classification: ${outcome.classification}\n" +
                    "Confidence: ${outcome.confidence}\n" +
                    "Rationale: ${outcome.rationale}"
                is ManualAiResult.Failure -> "Remote AI server: ${outcome.message}\nLocal SMS detection is unchanged."
            }
            analyzeButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        if (::apiKey.isInitialized) apiKey.text?.clear()
        super.onDestroy()
    }
}
