package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.model.entity.Keyword
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy regression cases with simulated Gemma responses; not measured model accuracy. */
class DetectionRegressionTest {
    private val seed: List<Keyword> by lazy {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(File("src/main/assets/keywords_seed.xml"))
        val entries = document.getElementsByTagName("keyword")
        (0 until entries.length).map { index ->
            val entry = entries.item(index).attributes
            Keyword(
                pattern = entry.getNamedItem("pattern").nodeValue,
                patternType = entry.getNamedItem("type").nodeValue,
                weight = entry.getNamedItem("weight").nodeValue.toFloat()
            )
        }
    }

    private fun classify(text: String, ai: GemmaResult): RiskLevel {
        val seededMatches = seed.filter { RuleWeightPolicy.weight(it) > 0f &&
            KeywordPatternMatcher.matches(text, it.pattern, it.patternType) }
        val matches = seededMatches + UrlIndicatorDetector.detect(text, seededMatches)
        val score = RiskScorer().compute(matches)
        val rule = ThresholdEvaluator().evaluate(score)
        val strong = MessageSignals.hasStrongRuleSignal(text, matches)
        val lowEvidence = MessageSignals.isLowEvidenceConversation(text, score, strong, 0)
        val verdict = FinalVerdictResolver.resolve(rule, ai, false, strong, lowEvidence)
        val points = CombinedRiskScorer.score(score, strong, 0, 0, ai,
            isLowEvidenceMessage = lowEvidence).total
        return if (SimpleGreetingPolicy.isEligible(text, score, strong, 0)) {
            SimpleGreetingPolicy.verdict(0)
        } else CombinedRiskScorer.withSuspiciousFloor(verdict.riskLevel, points)
    }

    private fun scam(confidence: String = "HIGH") =
        GemmaResult("SCAM", confidence, "A suspicious request was made.")
    private fun legitimate(confidence: String = "HIGH") =
        GemmaResult("LEGITIMATE", confidence, "No deceptive request was found.")

    @Test fun neutralEnglishFilipinoAndBisayaMessagesResistUnsupportedModelScam() {
        listOf("hello jhunren", "gio", "This is a test message",
            "hi pila kilo sa kanding?", "Kumusta, asa ka karon?", "Nasa bahay na ako")
            .forEach { assertEquals(it, RiskLevel.SAFE, classify(it, scam())) }
    }

    @Test fun routineServiceAndPromotionCanBeSafeWhenModelFindsNoFraud() {
        listOf("Your package is out for delivery tomorrow.",
            "GCash rewards are available this week in the app.",
            "Limited time sale, visit our store for details.")
            .forEach { assertEquals(it, RiskLevel.SAFE, classify(it, legitimate())) }
    }

    @Test fun weakModelSuspicionOnPromotionStaysUnderReview() {
        assertEquals(RiskLevel.SUSPICIOUS,
            classify("Limited time sale, visit our store for details.", scam()))
    }

    @Test fun suspiciousLinkCredentialRequestAndPrizeFeeRemainScam() {
        listOf("Open https://bdo-verify.example/pay to confirm your account",
            "Send your OTP now to keep your account active",
            "You have won. Claim your prize by paying a fee now.",
            "Ihatag imong PIN para makuha ang premyo")
            .forEach { assertEquals(it, RiskLevel.SCAM, classify(it, scam())) }
    }

    @Test fun shortFraudRequestIsNotExemptAndInferenceFailureAddsNoRisk() {
        assertTrue(MessageSignals.hasStrongRuleSignal("Send your OTP", emptyList()))
        assertEquals(RiskLevel.SCAM, classify("Send your OTP", scam()))
        assertEquals(RiskLevel.SAFE, classify("See you later", GemmaResult.fallback()))
        assertEquals(RiskLevel.SUSPICIOUS,
            classify("Claim your prize. Click the link to continue", GemmaResult.fallback()))
    }
}
