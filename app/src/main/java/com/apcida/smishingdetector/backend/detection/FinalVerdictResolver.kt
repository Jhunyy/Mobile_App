package com.apcida.smishingdetector.backend.detection

import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.model.data.RiskLevel
import com.apcida.smishingdetector.util.Constants

/**
 * Combines the rule-based detector and Gemma contextual analysis.
 *
 * Gemma confidence is used when deciding how strongly its classification
 * should affect the final verdict.
 *
 * Principles:
 * - HIGH/MEDIUM confidence SCAM can strongly influence the result.
 * - LOW confidence SCAM cannot independently produce a full SCAM verdict.
 * - LEGITIMATE Gemma results cannot blindly erase strong rule-based evidence.
 * - A saved contact can clear a suspicious rule result only with high-confidence
 *   LEGITIMATE analysis and no strong rule signal.
 * - LOW-confidence LEGITIMATE results are treated cautiously.
 * - Failed/unavailable Gemma analysis falls back to the rule-based result.
 */
object FinalVerdictResolver {

    data class Verdict(
        val riskLevel: RiskLevel,
        val isFlagged: Boolean
    )

    private enum class Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN
    }

    fun resolve(ruleRiskLevel: RiskLevel, gemmaResult: GemmaResult): Verdict =
        resolve(ruleRiskLevel, gemmaResult, isSavedContact = false, hasStrongRuleSignal = false)

    fun resolve(
        ruleRiskLevel: RiskLevel,
        gemmaResult: GemmaResult,
        isSavedContact: Boolean,
        hasStrongRuleSignal: Boolean
    ): Verdict {

        /*
         * If Gemma failed or produced no usable result,
         * preserve the rule-based detector's evidence.
         */
        if (!gemmaResult.isSuccessful) {
            return ruleFallback(ruleRiskLevel)
        }

        val confidence = parseConfidence(gemmaResult.confidence)

        return when (gemmaResult.classification) {

            Constants.GEMMA_SCAM -> {
                resolveGemmaScam(
                    ruleRiskLevel = ruleRiskLevel,
                    confidence = confidence
                )
            }

            Constants.GEMMA_LEGITIMATE -> {
                resolveGemmaLegitimate(
                    ruleRiskLevel = ruleRiskLevel,
                    confidence = confidence,
                    canClearContact = isSavedContact && !hasStrongRuleSignal
                )
            }

            else -> {
                ruleFallback(ruleRiskLevel)
            }
        }
    }

    /**
     * Gemma believes the message is a SCAM.
     */
    private fun resolveGemmaScam(
        ruleRiskLevel: RiskLevel,
        confidence: Confidence
    ): Verdict {

        return when (confidence) {

            /*
             * Strong AI evidence is enough to classify the
             * message as SCAM.
             */
            Confidence.HIGH -> {
                Verdict(
                    RiskLevel.SCAM,
                    isFlagged = true
                )
            }

            /*
             * Medium confidence still flags the message as SCAM.
             *
             * The UI can later present this using a softer warning
             * than a HIGH-confidence SCAM if desired.
             */
            Confidence.MEDIUM -> {
                Verdict(
                    RiskLevel.SCAM,
                    isFlagged = true
                )
            }

            /*
             * LOW-confidence AI should not independently make
             * a full SCAM decision.
             *
             * However, if the rule engine already independently
             * classified the message as SCAM, preserve that result.
             */
            Confidence.LOW,
            Confidence.UNKNOWN -> {

                when (ruleRiskLevel) {

                    RiskLevel.SCAM -> {
                        Verdict(
                            RiskLevel.SCAM,
                            isFlagged = true
                        )
                    }

                    RiskLevel.SUSPICIOUS,
                    RiskLevel.SAFE -> {
                        Verdict(
                            RiskLevel.SUSPICIOUS,
                            isFlagged = true
                        )
                    }
                }
            }
        }
    }

    /**
     * Gemma believes the message is LEGITIMATE.
     */
    private fun resolveGemmaLegitimate(
        ruleRiskLevel: RiskLevel,
        confidence: Confidence,
        canClearContact: Boolean
    ): Verdict {

        return when (confidence) {

            /*
             * A saved contact can clear SUSPICIOUS evidence only when
             * no strong rule signal was found. SCAM evidence stays flagged.
             */
            Confidence.HIGH -> {

                when (ruleRiskLevel) {

                    RiskLevel.SAFE -> {
                        Verdict(
                            RiskLevel.SAFE,
                            isFlagged = false
                        )
                    }

                    RiskLevel.SUSPICIOUS -> {
                        if (canClearContact) Verdict(RiskLevel.SAFE, isFlagged = false)
                        else Verdict(RiskLevel.SUSPICIOUS, isFlagged = true)
                    }

                    RiskLevel.SCAM -> {
                        Verdict(
                            RiskLevel.SUSPICIOUS,
                            isFlagged = true
                        )
                    }
                }
            }

            /*
             * MEDIUM confidence is not strong enough to erase
             * a rule-based SCAM classification.
             */
            Confidence.MEDIUM -> {

                when (ruleRiskLevel) {

                    RiskLevel.SAFE -> {
                        Verdict(
                            RiskLevel.SAFE,
                            isFlagged = false
                        )
                    }

                    RiskLevel.SUSPICIOUS -> {
                        Verdict(
                            RiskLevel.SUSPICIOUS,
                            isFlagged = true
                        )
                    }

                    RiskLevel.SCAM -> {
                        Verdict(
                            RiskLevel.SCAM,
                            isFlagged = true
                        )
                    }
                }
            }

            /*
             * LOW-confidence LEGITIMATE analysis should not be
             * trusted enough to clear a message.
             */
            Confidence.LOW,
            Confidence.UNKNOWN -> {

                when (ruleRiskLevel) {

                    RiskLevel.SCAM -> {
                        Verdict(
                            RiskLevel.SCAM,
                            isFlagged = true
                        )
                    }

                    RiskLevel.SUSPICIOUS,
                    RiskLevel.SAFE -> {
                        Verdict(
                            RiskLevel.SUSPICIOUS,
                            isFlagged = true
                        )
                    }
                }
            }
        }
    }

    /**
     * Used when Gemma is unavailable, fails, or produces
     * an unknown classification.
     */
    private fun ruleFallback(
        ruleRiskLevel: RiskLevel
    ): Verdict {

        return when (ruleRiskLevel) {

            RiskLevel.SAFE -> {
                Verdict(
                    RiskLevel.SUSPICIOUS,
                    isFlagged = true
                )
            }

            RiskLevel.SUSPICIOUS -> {
                Verdict(
                    RiskLevel.SUSPICIOUS,
                    isFlagged = true
                )
            }

            RiskLevel.SCAM -> {
                Verdict(
                    RiskLevel.SCAM,
                    isFlagged = true
                )
            }
        }
    }

    /**
     * Converts Gemma's stored confidence into a normalized value.
     *
     * toString() is intentionally used so this remains compatible
     * whether confidence is currently stored as a String or enum-like value.
     */
    private fun parseConfidence(confidence: Any?): Confidence {

        return when (
            confidence
                ?.toString()
                ?.trim()
                ?.uppercase()
        ) {

            "HIGH" -> Confidence.HIGH

            "MEDIUM" -> Confidence.MEDIUM

            "LOW" -> Confidence.LOW

            else -> Confidence.UNKNOWN
        }
    }
}


