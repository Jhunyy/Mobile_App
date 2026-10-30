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
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredential
import com.apcida.smishingdetector.backend.network.CloudflareAccessCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionCredentialStore
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import com.apcida.smishingdetector.backend.network.RemoteTransportGate
import com.apcida.smishingdetector.backend.remote.ProductionSmsTransport
import com.apcida.smishingdetector.backend.remote.RemoteAnalysisReply
import com.apcida.smishingdetector.backend.remote.RemoteAuthorizationGate
import com.apcida.smishingdetector.backend.remote.RemoteSmsCandidate
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Manual debug fixture. It has no inbox or SMS receiver access and never opens the production gate. */
class CloudflareSyntheticActivity : AppCompatActivity() {
    private lateinit var backend: ProductionCredentialStore
    private lateinit var access: CloudflareAccessCredentialStore
    private lateinit var backendInput: EditText
    private lateinit var inviteInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var clientSecretInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var confirmed: CheckBox
    private lateinit var result: TextView
    private lateinit var analyze: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = ProductionCredentialStore(this)
        access = CloudflareAccessCredentialStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(value: String) = TextView(this).apply { text = value; textSize = 16f }
        fun secretInput(hintText: String) = EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        layout.addView(label("Cloudflare synthetic test · debug only"))
        layout.addView(label("Approved host: ${BuildConfig.PRODUCTION_APPROVED_HOST.ifBlank { "Not configured" }}"))
        layout.addView(label("Use a temporary test installation. Never enter a real SMS here."))
        inviteInput = secretInput("One-time beta invite code").also(layout::addView)
        layout.addView(Button(this).apply {
            text = "Set up this test phone with invite"
            setOnClickListener {
                if (backend.hasStoredCredential() || access.hasStoredCredential()) {
                    result.text = "Remove existing test credentials before using an invite."
                    return@setOnClickListener
                }
                val code = inviteInput.text.toString().trim()
                inviteInput.text?.clear()
                isEnabled = false
                result.text = "Setting up this test phone…"
                val button = this
                lifecycleScope.launch {
                    result.text = try {
                        withContext(Dispatchers.IO) {
                            val bundle = BetaInviteClient.redeem(BuildConfig.PRODUCTION_APPROVED_HOST, code)
                            try {
                                backend.provision(bundle.installationToken)
                                access.provision(bundle.clientId, bundle.clientSecret)
                            } catch (error: Exception) {
                                backend.clear()
                                access.clear()
                                throw error
                            }
                        }
                        "This test phone has its own credentials. Use invented text only."
                    } catch (_: Exception) {
                        "Setup failed. Check the invite and connection; ask the owner for a new invite if needed."
                    }
                    button.isEnabled = true
                }
            }
        })
        layout.addView(label("Invite setup is for a supervised beta. The owner must enable the server enrollment route first."))
        backendInput = secretInput("Backend installation token").also(layout::addView)
        clientIdInput = secretInput("Cloudflare Client ID").also(layout::addView)
        clientSecretInput = secretInput("Cloudflare Client Secret").also(layout::addView)
        layout.addView(Button(this).apply {
            text = "Store test credentials securely"
            setOnClickListener {
                try {
                    if (backend.hasStoredCredential() || access.hasStoredCredential()) {
                        result.text = "Test credentials already exist. Remove them before replacing."
                    } else {
                        CloudflareAccessCredential(clientIdInput.text.toString().trim(),
                            clientSecretInput.text.toString().trim())
                        backend.provision(backendInput.text.toString().trim())
                        access.provision(clientIdInput.text.toString().trim(),
                            clientSecretInput.text.toString().trim())
                        result.text = "Test credentials stored in Android Keystore."
                    }
                } catch (_: Exception) {
                    result.text = "Could not store credentials. Check all three values."
                } finally {
                    backendInput.text?.clear()
                    clientIdInput.text?.clear()
                    clientSecretInput.text?.clear()
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Remove local test credentials"
            setOnClickListener {
                try {
                    if (AiProtectionConsentStore(this@CloudflareSyntheticActivity).remoteDeletionPending()) {
                        result.text = "Resolve pending remote deletion before removing credentials."
                    } else {
                        backend.clear()
                        access.clear()
                        result.text = "Local credentials removed. Revoke the test token on the server too."
                    }
                } catch (_: Exception) { result.text = "Could not remove credential." }
            }
        })
        messageInput = EditText(this).apply {
            hint = "Type a synthetic SMS (no real message)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }.also(layout::addView)
        confirmed = CheckBox(this).apply {
            text = "I confirm this is invented test text, not a real SMS"
        }.also(layout::addView)
        analyze = Button(this).apply {
            text = "Analyze synthetic text"
            setOnClickListener { sendSynthetic() }
        }.also(layout::addView)
        result = label("No test sent.").also(layout::addView)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun sendSynthetic() {
        val text = messageInput.text.toString()
        val host = BuildConfig.PRODUCTION_APPROVED_HOST
        val origin = "https://$host"
        if (!confirmed.isChecked || text.isBlank() || text.length > 2000) {
            result.text = "Enter invented test text and confirm it is synthetic."
            return
        }
        if (!ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, host) ||
            backend.get() == null || access.get() == null) {
            result.text = "Approved host or test credentials are missing."
            return
        }
        analyze.isEnabled = false
        result.text = "Contacting protected test server…"
        val now = System.currentTimeMillis()
        val syntheticConsent = AiProtectionConsentStore.Snapshot(true, now - 1, 1)
        val requirements = RemoteTransportGate.Requirements(syntheticConsent, origin, host,
            true, true, true, true)
        val transport = ProductionSmsTransport(origin, host, backend, access,
            { requirements }, RemoteAuthorizationGate { RemoteTransportGate.requirementsSatisfied(it) })
        val candidate = RemoteSmsCandidate(1, "ManualTest", text, now, UUID.randomUUID().toString(),
            syntheticConsent.generation, historicalImport = false)
        lifecycleScope.launch {
            val outcome = transport.analyze(candidate)
            result.text = when (outcome) {
                is RemoteAnalysisReply.Verified ->
                    "${outcome.classification} · ${outcome.confidence}\n${outcome.rationale}"
                is RemoteAnalysisReply.Failed -> "Test unavailable: ${outcome.reason}"
            }
            analyze.isEnabled = true
        }
    }

    override fun onDestroy() {
        if (::inviteInput.isInitialized) inviteInput.text?.clear()
        if (::backendInput.isInitialized) backendInput.text?.clear()
        if (::clientIdInput.isInitialized) clientIdInput.text?.clear()
        if (::clientSecretInput.isInitialized) clientSecretInput.text?.clear()
        super.onDestroy()
    }
}
