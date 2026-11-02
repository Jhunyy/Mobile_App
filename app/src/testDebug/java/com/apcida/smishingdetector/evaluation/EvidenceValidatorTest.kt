package com.apcida.smishingdetector.evaluation

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceValidatorTest {
    private fun scam(reason: String) = GemmaResult("SCAM", "HIGH", reason)

    @Test fun plainConversationAndUnsupportedExplanationsAreNotStrongFraud() {
        listOf("Maria", "kumusta ka?", "pila ang kilo sa isda?", "HAHAHAHA").forEach { text ->
            val assessment = EvidenceValidator.assess(text,
                scam("It asks for an OTP at a suspicious link."))
            assertEquals(EvidenceTier.UNSUPPORTED, assessment.tier)
            assertEquals("LEGITIMATE", assessment.experimentalClassification)
            assertTrue(assessment.unsupportedReasonClaims.contains("credential_claim"))
            assertTrue(assessment.unsupportedReasonClaims.contains("link_claim"))
        }
    }

    @Test fun ordinaryPromotionPaymentAndUnknownLinkRemainAmbiguous() {
        listOf("20% off shoes this weekend", "Please pay the lunch bill tomorrow",
            "See https://example.org for opening hours", "You won a raffle").forEach { text ->
            assertEquals(EvidenceTier.AMBIGUOUS,
                EvidenceValidator.assess(text, scam("Possibly deceptive")).tier)
        }
    }

    @Test fun explicitCredentialAndPrizeFeeSchemesAreStrong() {
        listOf("Send your bank OTP to unlock your account",
            "I-verify ang account sa https://verify.example.org ug ilagay ang password",
            "Ipadala ang PIN aron ma-access ang wallet",
            "You won a prize. Pay a processing fee now to claim it.",
            "Your bank account is blocked. Pay the release fee now.").forEach { text ->
            assertEquals(text, EvidenceTier.STRONG_FRAUD,
                EvidenceValidator.assess(text, scam("Fraud request")).tier)
        }
    }

    @Test fun safetyNoticeAndModelFailureDoNotCreateFraudEvidence() {
        assertTrue(EvidenceValidator.assess("Never share your OTP with anyone.",
            scam("This asks for an OTP")).tier != EvidenceTier.STRONG_FRAUD)
        assertEquals(EvidenceTier.NOT_APPLICABLE,
            EvidenceValidator.assess("Send your OTP", GemmaResult.fallback()).tier)
    }

    @Test fun batchKeepsProductionDecisionAndSeparateExperimentalMetrics() = runBlocking {
        val runner = EvaluationRunner(
            EvaluationRules { RuleEvidence(0f, RiskLevel.SAFE, emptyList(), false) },
            EvaluationGemma { _, _, _, _ -> scam("It asks for an OTP through a link") }
        )
        val report = runner.run(listOf(
            EvaluationInput("1", "pila ang kilo sa isda?", RiskLevel.SAFE),
            EvaluationInput("2", "Send your OTP to unlock your bank account", RiskLevel.SCAM)
        ), EvaluationMetadata("run", "v1", "gemma", "2026-10-26T00:00:00Z"))
        assertEquals(2, report.rawGemmaBinary().tp + report.rawGemmaBinary().fp)
        assertEquals(EvidenceTier.UNSUPPORTED.name, report.records[0].evidenceTier)
        assertEquals(EvidenceTier.STRONG_FRAUD.name, report.records[1].evidenceTier)
        assertEquals(0, report.evidenceModelBinary(false).fp)
        assertEquals(1, report.evidenceModelBinary(false).tp)
        assertTrue(report.detailsCsv().contains("experimental_final_verdict"))
        assertTrue(report.summaryCsv().contains("RAW_GEMMA_SCAM"))
        assertTrue(report.summaryCsv().contains("EXPERIMENTAL_FINAL_STRICT"))
    }
}
