package com.apcida.smishingdetector.pilot

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apcida.smishingdetector.BuildConfig
import com.apcida.smishingdetector.backend.network.PilotCredentialStore
import com.apcida.smishingdetector.backend.network.PilotResult
import com.apcida.smishingdetector.backend.network.PilotSmsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug launcher for manually entered synthetic text. It has no inbox access. */
class PilotManualActivity : AppCompatActivity() {
    private lateinit var store: PilotCredentialStore
    private lateinit var endpoint: EditText
    private lateinit var credential: EditText
    private lateinit var sms: EditText
    private lateinit var confirmed: CheckBox
    private lateinit var result: TextView
    private lateinit var analyze: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PilotCredentialStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 17f }
        layout.addView(label("Secure pilot · synthetic messages only"))
        layout.addView(label("Approved host: ${BuildConfig.PILOT_APPROVED_HOST.ifBlank { "Not configured" }}"))
        endpoint = EditText(this).apply {
            hint = "https://approved-pilot-host"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            if (BuildConfig.PILOT_APPROVED_HOST.isNotBlank()) {
                setText("https://${BuildConfig.PILOT_APPROVED_HOST}")
            }
        }
        layout.addView(endpoint)
        credential = EditText(this).apply {
            hint = "One-time installation credential"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        layout.addView(credential)
        layout.addView(Button(this).apply {
            text = "Store installation credential securely"
            setOnClickListener {
                try {
                    store.provision(credential.text.toString().trim())
                    result.text = "Installation credential stored in Android Keystore."
                } catch (_: Exception) {
                    result.text = "Could not store credential. Check its format."
                } finally { credential.text?.clear() }
            }
        })
        layout.addView(Button(this).apply {
            text = "Remove installation credential"
            setOnClickListener { store.clear(); result.text = "Credential removed from this device." }
        })
        sms = EditText(this).apply {
            hint = "Enter a synthetic SMS"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        layout.addView(sms)
        confirmed = CheckBox(this).apply { text = "I confirm this is synthetic text, not a real SMS" }
        layout.addView(confirmed)
        analyze = Button(this).apply {
            text = "Analyze synthetic message"
            setOnClickListener { sendSynthetic() }
        }
        layout.addView(analyze)
        result = label("No pilot request sent.")
        layout.addView(result)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun sendSynthetic() {
        if (!confirmed.isChecked) {
            result.text = "Confirm that the text is synthetic first."
            return
        }
        analyze.isEnabled = false
        result.text = "Contacting secure pilot server…"
        val origin = endpoint.text.toString()
        val text = sms.text.toString()
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PilotSmsClient(store).analyzeSynthetic(origin, BuildConfig.PILOT_APPROVED_HOST, text)
            }
            result.text = when (outcome) {
                is PilotResult.Success -> "Remote Gemma + RAG: ${outcome.classification}\n" +
                    "Confidence: ${outcome.confidence}\nRationale: ${outcome.rationale}"
                is PilotResult.Failure -> outcome.message
            }
            analyze.isEnabled = true
        }
    }

    override fun onDestroy() {
        if (::credential.isInitialized) credential.text?.clear()
        super.onDestroy()
    }
}
