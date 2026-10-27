package com.apcida.smishingdetector.backend.phase3e

import com.apcida.smishingdetector.backend.detection.BackendKeywordRules
import org.junit.Assert.*
import org.junit.Test

/** Golden outputs generated from frozen backend rules.js RULES_VERSION=2. */
class BackendKeywordRulesParityTest {
    data class Case(val sms: String, val score: Int, val verdict: String, val codes: List<String>)
    private val cases = listOf(
        Case("Your OTP is 123456. Never share this code with anyone.", 0, "SAFE", emptyList()),
        Case("Hi! Dinner is at 7. See you soon.", 0, "SAFE", emptyList()),
        Case("We are BDO security team. Reply with your OTP now to stop account suspension.", 60, "SCAM",
            listOf("CREDENTIAL_REQUEST", "AUTHORITY_CLAIM", "COERCIVE_URGENCY")),
        Case("Mama here, new number. Do not call. Send money to this wallet now, emergency.", 60, "SCAM",
            listOf("NEW_NUMBER_MONEY_REQUEST", "FAMILY_IDENTITY_CLAIM", "COERCIVE_URGENCY")),
        Case("Buy gift cards and send codes to me today.", 60, "SCAM",
            listOf("GIFT_CARD_REQUEST", "COERCIVE_URGENCY")),
        Case("Install this APK to verify your SIM: https://verify-sim.xyz/app.apk", 90, "SCAM",
            listOf("SUSPICIOUS_URL", "UNSAFE_APP_INSTALL")),
        Case("Your BPI account statement is ready.", 0, "SAFE", emptyList()),
        Case("Visit https://example.com for our hours.", 5, "SAFE", listOf("LINK_PRESENT")),
        Case("Enter your password at https://login-check.xyz to claim refund.", 105, "SCAM",
            listOf("SUSPICIOUS_URL", "CREDENTIAL_FORM_REQUEST", "GOVERNMENT_PAYMENT_CLAIM")),
        Case("Government tax fine: pay first to this personal wallet today.", 60, "SCAM",
            listOf("ADVANCE_FEE", "GOVERNMENT_PAYMENT_CLAIM", "COERCIVE_URGENCY")),
        Case("Kami sa BPI kailangan namin OTP. Ipadala ngayon para hindi ma-suspend ang account.", 60, "SCAM",
            listOf("CREDENTIAL_REQUEST", "AUTHORITY_CLAIM", "COERCIVE_URGENCY")),
        Case("Bayri una ang processing fee sa bagong personal wallet para sa scholarship.", 60, "SCAM",
            listOf("ADVANCE_FEE", "JOB_OR_SCHOLARSHIP_CLAIM")),
        Case("I-install ang APK sa https://sim-update.top ug ihatag ang verification code.", 105, "SCAM",
            listOf("SUSPICIOUS_URL", "CREDENTIAL_REQUEST", "UNSAFE_APP_INSTALL")),
        Case("Your verification code is 418920. Do not share it with anyone.", 0, "SAFE", emptyList())
    )
    @Test fun matchesBackendReferenceFixtures() {
        assertEquals(2, BackendKeywordRules.VERSION)
        cases.forEach { c ->
            val result = BackendKeywordRules.analyze(c.sms)
            assertEquals(c.sms, c.score, result.score)
            assertEquals(c.sms, c.verdict, result.classification.name)
            assertEquals(c.sms, c.codes, result.matchedIndicators.map { it.code })
        }
    }
    @Test fun duplicateIndicatorsDoNotStack() {
        val once = BackendKeywordRules.analyze("Buy gift cards today")
        val repeated = BackendKeywordRules.analyze("Buy gift cards gift cards gift cards today")
        assertEquals(once.score, repeated.score)
    }
}
