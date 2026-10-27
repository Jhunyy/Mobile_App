package com.apcida.smishingdetector.view.fragment

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.ContactNameResolver
import com.apcida.smishingdetector.util.PermissionHelper
import com.apcida.smishingdetector.backend.worker.SmsInboxSyncWorker
import com.apcida.smishingdetector.backend.worker.ProductionRevocationWorker
import com.apcida.smishingdetector.backend.consent.AiProtectionConsentStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private lateinit var aiSwitch: SwitchCompat
    private lateinit var deletionStatus: TextView
    private lateinit var smsSwitch: SwitchCompat
    private lateinit var notificationSwitch: SwitchCompat
    private lateinit var namesSwitch: SwitchCompat
    private lateinit var contactsSwitch: SwitchCompat

    private val requestSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PermissionHelper.hasReadSmsPermission(requireContext())) {
            SmsInboxSyncWorker.enqueue(requireContext().applicationContext)
        }
        refreshSwitches()
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) preferences().edit().putBoolean(Constants.PREF_ALERTS_ENABLED, false).apply()
        refreshSwitches()
    }
    private val requestContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshSwitches()
    }
    private val requestContactsForNames = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) preferences().edit().putBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, true).apply()
        refreshSwitches()
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(32))
        }
        scroll.addView(body)

        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(icon(R.drawable.ic_settings, dp(40)), LinearLayout.LayoutParams(dp(42), dp(42)))
        heading.addView(label("Settings", 30f, true, R.color.inbox_text).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(ContextCompat.getColor(context, R.color.inbox_muted))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Open Android app settings"
            setOnClickListener { openAppSettings("Manage ApCiDa permissions in Android App settings.") }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        body.addView(heading)
        body.addView(label("Manage your protection and preferences", 15f, false, R.color.inbox_muted).apply {
            setPadding(dp(54), 0, 0, 0)
        })

        section(body, "AI Protection", 26)
        val protection = card(body)
        aiSwitch = switchRow(protection, R.drawable.ic_safety, "AI Protection",
            "Consent to remote analysis of each new SMS. Secure service setup is pending; local keyword warnings remain active.") { enabled ->
            val store = AiProtectionConsentStore(requireContext())
            if (!enabled) {
                store.revoke()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    store.reconcileRevokedPending()
                }
                refreshSwitches()
            } else if (!PermissionHelper.hasSmsPermissions(requireContext())) {
                AlertDialog.Builder(requireContext())
                    .setMessage("Grant SMS permission before enabling AI Protection.")
                    .setPositiveButton("OK", null).show()
                refreshSwitches()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("Enable AI Protection?")
                    .setMessage("ApCiDa will automatically send each newly received SMS text, sender, receipt time and request ID to its remote Gemma 3 12B + RAG service. Local keyword warnings continue if the service is unavailable. You can revoke this in Settings; requests already sent cannot be recalled. Research contribution is separate. Automatic sending stays disabled until secure HTTPS and per-install authentication are ready.")
                    .setNegativeButton("Cancel") { _, _ -> refreshSwitches() }
                    .setPositiveButton("I agree") { _, _ -> store.grant(); refreshSwitches() }
                    .show()
            }
        }
        protection.addView(label("Current service status: remote automatic analysis unavailable; keyword-only protection active.", 13f, false, R.color.inbox_muted).apply {
            setPadding(dp(12), dp(4), dp(12), dp(12))
        })
        deletionStatus = label("", 13f, false, R.color.inbox_muted).apply {
            setPadding(dp(12), dp(4), dp(12), dp(12))
            setOnClickListener {
                ProductionRevocationWorker.enqueueIfPending(requireContext(), replace = true)
                refreshSwitches()
            }
        }
        protection.addView(deletionStatus)

        section(body, "Permissions", 26)
        val permissions = card(body)
        smsSwitch = switchRow(permissions, R.drawable.ic_messages, "SMS Access", "Allow ApCiDa to view SMS messages") {
            if (it) requestSms.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
            else openAppSettings("Android manages SMS access. Turn off the SMS permissions in App settings.")
        }
        divider(permissions)
        notificationSwitch = switchRow(permissions, R.drawable.ic_notifications, "Notifications", "Allow ApCiDa to send you notifications") { enabled ->
            preferences().edit().putBoolean(Constants.PREF_ALERTS_ENABLED, enabled).apply()
            if (enabled && Build.VERSION.SDK_INT >= 33 && !PermissionHelper.hasNotificationPermission(requireContext())) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (enabled && !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                openAppSettings("Notifications are blocked by Android. Allow ApCiDa notifications in App settings.")
            } else refreshSwitches()
        }
        divider(permissions)
        namesSwitch = switchRow(permissions, R.drawable.ic_person, "Show Contact Names", "Display saved names instead of numbers") { enabled ->
            if (enabled && !ContactNameResolver.hasPermission(requireContext())) {
                requestContactsForNames.launch(Manifest.permission.READ_CONTACTS)
            } else {
                preferences().edit().putBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, enabled).apply()
                refreshSwitches()
            }
        }
        divider(permissions)
        contactsSwitch = switchRow(permissions, R.drawable.ic_groups, "Contacts Access", "Allow ApCiDa to access your contacts") { enabled ->
            if (enabled) requestContacts.launch(Manifest.permission.READ_CONTACTS)
            else openAppSettings("Android manages contacts access. Turn it off in App settings.")
        }

        section(body, "Community", 22)
        val community = card(body)
        linkRow(community, R.drawable.ic_groups, "Help the Community", "Contribute up to 20 scam and 20 safe messages to improve detection") {
            findNavController().navigate(R.id.communityFragment)
        }
        community.addView(label("ⓘ   Selected messages will be reviewed before sharing.", 13f, false, R.color.inbox_safe).apply {
            background = rounded(0xFFE8F8F0.toInt(), 0, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })

        section(body, "Safety Tips", 22)
        linkRow(card(body), R.drawable.ic_safety, "Safety Tips", "Learn how to spot and avoid scams") {
            findNavController().navigate(R.id.safetyTipsFragment)
        }
        return scroll
    }

    override fun onResume() {
        super.onResume()
        if (::smsSwitch.isInitialized) refreshSwitches()
    }

    private fun refreshSwitches() {
        val context = requireContext()
        val protectionStore = AiProtectionConsentStore(context)
        aiSwitch.isChecked = protectionStore.snapshot().enabled
        if (::deletionStatus.isInitialized) {
            val status = protectionStore.remoteDeletionStatus()
            deletionStatus.text = when (status) {
                AiProtectionConsentStore.RemoteDeletionStatus.NOT_REQUIRED -> "No remote deletion pending."
                AiProtectionConsentStore.RemoteDeletionStatus.PENDING ->
                    "Remote cache deletion pending. Tap to retry."
                AiProtectionConsentStore.RemoteDeletionStatus.CONFIGURATION_REQUIRED ->
                    "Remote deletion pending: approved server not configured. Tap after setup to retry."
                AiProtectionConsentStore.RemoteDeletionStatus.RETRY_PAUSED ->
                    "Remote deletion pending: server unavailable. Tap to retry."
                AiProtectionConsentStore.RemoteDeletionStatus.CREDENTIAL_UNAVAILABLE ->
                    "Remote deletion unconfirmed: credential unavailable. Contact the pilot administrator."
                AiProtectionConsentStore.RemoteDeletionStatus.AUTH_REJECTED ->
                    "Remote deletion unconfirmed: authentication rejected. Contact the pilot administrator; tap after correction to retry."
                AiProtectionConsentStore.RemoteDeletionStatus.CONFIRMED ->
                    "Remote installation revoked and cached responses deleted."
            }
            deletionStatus.isClickable = protectionStore.remoteDeletionPending() &&
                status != AiProtectionConsentStore.RemoteDeletionStatus.CREDENTIAL_UNAVAILABLE
        }
        smsSwitch.isChecked = PermissionHelper.hasSmsPermissions(context)
        notificationSwitch.isChecked = preferences().getBoolean(Constants.PREF_ALERTS_ENABLED, true) &&
            PermissionHelper.hasNotificationPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        contactsSwitch.isChecked = ContactNameResolver.hasPermission(context)
        namesSwitch.isChecked = ContactNameResolver.isEnabled(context)
    }

    private fun openAppSettings(message: String) {
        AlertDialog.Builder(requireContext()).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)))
            }.show()
        refreshSwitches()
    }

    private fun section(parent: LinearLayout, title: String, top: Int) {
        parent.addView(label(title, 16f, true, R.color.inbox_muted),
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(top), 0, dp(10)) })
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 0xFFE2E6EA.toInt(), 15)
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun switchRow(parent: LinearLayout, iconId: Int, title: String, detail: String,
                          onChange: (Boolean) -> Unit): SwitchCompat {
        val row = baseRow(parent, iconId, title, detail)
        val toggle = SwitchCompat(requireContext()).apply {
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF00A86B.toInt(), 0xFFDDE0E5.toInt()))
            setOnClickListener { onChange(isChecked) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(54), dp(48)))
        return toggle
    }

    private fun linkRow(parent: LinearLayout, iconId: Int, title: String, detail: String, action: () -> Unit) {
        val row = baseRow(parent, iconId, title, detail)
        row.addView(label("›", 30f, false, R.color.inbox_muted))
        row.setOnClickListener { action() }
    }

    private fun baseRow(parent: LinearLayout, iconId: Int, title: String, detail: String): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            minimumHeight = dp(72)
        }
        val iconBack = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFE0F8EB.toInt(), 0, 50)
        }
        iconBack.addView(icon(iconId, dp(25)))
        row.addView(iconBack, LinearLayout.LayoutParams(dp(48), dp(48)))
        val text = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 16f, true, R.color.inbox_text))
        text.addView(label(detail, 13f, false, R.color.inbox_muted))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(14), 0, dp(4), 0) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(requireContext()).apply { setBackgroundColor(0xFFE8EAED.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int): TextView =
        TextView(requireContext()).apply {
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(requireContext(), color))
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    private fun icon(id: Int, size: Int): ImageView = ImageView(requireContext()).apply {
        setImageResource(id)
        setColorFilter(ContextCompat.getColor(requireContext(), R.color.inbox_green))
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun preferences() = requireContext().getSharedPreferences(Constants.PREFS_NAME, 0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
