package com.apcida.smishingdetector.view.presentation

/** Honest status copy; this build does not probe or contact the production server. */
object ProtectionStatusPresentation {
    fun remote(consentEnabled: Boolean, productionReady: Boolean, hostConfigured: Boolean): String =
        when {
            !consentEnabled -> "Remote AI: off. No automatic SMS upload is authorized."
            !productionReady -> "Remote AI: not connected in this build. Consent is saved; no automatic SMS is sent."
            !hostConfigured -> "Remote AI: approved server not configured. Keyword-only screening continues."
            else -> "Remote AI: configured; current server availability has not been checked."
        }

    fun local(smsPermission: Boolean): String = if (smsPermission)
        "Local keyword screening is active for new SMS. A message without warning indicators is still unverified."
    else "Local keyword screening needs SMS permission for new messages."

    fun credential(stored: Boolean, quarantined: Boolean, deletionPending: Boolean): String = when {
        deletionPending -> "Installation credential: held for deletion; analysis blocked."
        !stored -> "Installation credential: not enrolled."
        quarantined -> "Installation credential: rejected for analysis; contact the administrator."
        else -> "Installation credential: stored securely; server authorization not checked."
    }
}
