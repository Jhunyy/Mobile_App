package com.apcida.smishingdetector.evaluation

import com.apcida.smishingdetector.model.data.RiskLevel
import java.util.Locale
import kotlin.math.ceil

data class EvaluationMetadata(
    val evaluationRunId: String,
    val detectorVersion: String,
    val modelName: String,
    val evaluationTimestamp: String
)

data class EvaluationRecord(
    val messageId: String,
    val verifiedLabel: RiskLevel?,
    val messageText: String = "",
    val ruleScore: Float? = null,
    val ruleVerdict: RiskLevel? = null,
    val matchedIndicators: List<String> = emptyList(),
    val gemmaClassification: String? = null,
    val gemmaConfidence: String? = null,
    val gemmaReason: String? = null,
    val gemmaInferenceStatus: String = "NOT_RUN",
    val finalVerdict: RiskLevel? = null,
    val evidenceTier: String? = null,
    val supportedEvidenceCues: List<String> = emptyList(),
    val unsupportedReasonClaims: List<String> = emptyList(),
    val experimentalModelClassification: String? = null,
    val experimentalFinalVerdict: RiskLevel? = null,
    val inferenceTimeMs: Long? = null,
    val contextMode: String = "MESSAGE_ONLY",
    val error: String? = null
)

data class BinaryMetrics(val tp: Int, val fp: Int, val tn: Int, val fn: Int) {
    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator

    val accuracy get() = ratio(tp + tn, tp + tn + fp + fn)
    val precision get() = ratio(tp, tp + fp)
    val recall get() = ratio(tp, tp + fn)
    val f1 get() = if (precision == null || recall == null || precision!! + recall!! == 0.0)
        null else 2 * precision!! * recall!! / (precision!! + recall!!)
    val specificity get() = ratio(tn, tn + fp)
    val falsePositiveRate get() = ratio(fp, fp + tn)
    val falseNegativeRate get() = ratio(fn, fn + tp)
}

class EvaluationReport(val metadata: EvaluationMetadata, val records: List<EvaluationRecord>) {
    val evaluated = records.filter { it.verifiedLabel != null && it.finalVerdict != null && it.error == null }
    val correct = evaluated.count { it.verifiedLabel == it.finalVerdict }
    val suspicious = evaluated.count { it.finalVerdict == RiskLevel.SUSPICIOUS }
    val gemmaFailures = records.count { it.gemmaInferenceStatus == "UNAVAILABLE" }
    val gemmaSuccesses = records.count { it.gemmaInferenceStatus == "AVAILABLE" }
    val paired = evaluated.filter { it.gemmaInferenceStatus == "AVAILABLE" &&
        it.experimentalFinalVerdict != null && it.experimentalModelClassification != null }

    private fun metricsFor(rows: List<EvaluationRecord>, positive: (EvaluationRecord) -> Boolean) =
        BinaryMetrics(
            tp = rows.count { it.verifiedLabel == RiskLevel.SCAM && positive(it) },
            fp = rows.count { it.verifiedLabel == RiskLevel.SAFE && positive(it) },
            tn = rows.count { it.verifiedLabel == RiskLevel.SAFE && !positive(it) },
            fn = rows.count { it.verifiedLabel == RiskLevel.SCAM && !positive(it) }
        )

    fun rawGemmaBinary() = metricsFor(paired) { it.gemmaClassification == "SCAM" }
    fun productionPairedBinary(warningsArePositive: Boolean) = metricsFor(paired) {
        it.finalVerdict == RiskLevel.SCAM ||
            (warningsArePositive && it.finalVerdict == RiskLevel.SUSPICIOUS)
    }
    fun evidenceModelBinary(warningsArePositive: Boolean) = metricsFor(paired) {
        it.experimentalModelClassification == "SCAM" ||
            (warningsArePositive && it.experimentalModelClassification == "SUSPICIOUS")
    }
    fun experimentalFinalBinary(warningsArePositive: Boolean) = metricsFor(paired) {
        it.experimentalFinalVerdict == RiskLevel.SCAM ||
            (warningsArePositive && it.experimentalFinalVerdict == RiskLevel.SUSPICIOUS)
    }

    fun matrix(label: RiskLevel, verdict: RiskLevel): Int =
        evaluated.count { it.verifiedLabel == label && it.finalVerdict == verdict }

    fun binary(warningsArePositive: Boolean): BinaryMetrics {
        fun positive(verdict: RiskLevel): Boolean = verdict == RiskLevel.SCAM ||
            (warningsArePositive && verdict == RiskLevel.SUSPICIOUS)
        return BinaryMetrics(
            tp = evaluated.count { it.verifiedLabel == RiskLevel.SCAM && positive(it.finalVerdict!!) },
            fp = evaluated.count { it.verifiedLabel == RiskLevel.SAFE && positive(it.finalVerdict!!) },
            tn = evaluated.count { it.verifiedLabel == RiskLevel.SAFE && !positive(it.finalVerdict!!) },
            fn = evaluated.count { it.verifiedLabel == RiskLevel.SCAM && !positive(it.finalVerdict!!) }
        )
    }

    val averageInferenceMs: Double? get() {
        val samples = successfulTimes()
        return if (samples.isEmpty()) null else samples.average()
    }
    val medianInferenceMs: Double? get() {
        val samples = successfulTimes()
        if (samples.isEmpty()) return null
        val middle = samples.size / 2
        return if (samples.size % 2 == 1) samples[middle].toDouble()
        else (samples[middle - 1].toDouble() + samples[middle]) / 2
    }
    private fun successfulTimes() = records.filter { it.gemmaInferenceStatus == "AVAILABLE" }
        .mapNotNull { it.inferenceTimeMs }.sorted()
    val minimumInferenceMs: Long? get() = successfulTimes().firstOrNull()
    val maximumInferenceMs: Long? get() = successfulTimes().lastOrNull()
    val p95InferenceMs: Long? get() = successfulTimes().let { times ->
        if (times.isEmpty()) null else times[(ceil(times.size * 0.95).toInt() - 1)
            .coerceIn(0, times.lastIndex)]
    }

    fun detailsCsv(): String {
        val header = listOf("evaluation_run_id", "detector_version", "model_name", "evaluation_timestamp",
            "message_id", "message_text", "verified_label", "rule_score", "rule_verdict", "matched_indicators",
            "gemma_classification", "gemma_confidence", "gemma_reason", "gemma_inference_status",
            "final_verdict", "inference_time_ms", "context_mode", "error",
            "evidence_tier", "supported_evidence_cues", "unsupported_reason_claims",
            "experimental_model_classification", "experimental_final_verdict")
        return EvaluationCsv.encode(listOf(header) + records.map { record ->
            listOf(metadata.evaluationRunId, metadata.detectorVersion, metadata.modelName,
                metadata.evaluationTimestamp, record.messageId, record.messageText,
                record.verifiedLabel?.name,
                record.ruleScore, record.ruleVerdict?.name,
                record.matchedIndicators.joinToString(" | "), record.gemmaClassification,
                record.gemmaConfidence, record.gemmaReason, record.gemmaInferenceStatus,
                record.finalVerdict?.name, record.inferenceTimeMs, record.contextMode, record.error,
                record.evidenceTier, record.supportedEvidenceCues.joinToString(" | "),
                record.unsupportedReasonClaims.joinToString(" | "),
                record.experimentalModelClassification, record.experimentalFinalVerdict?.name)
        })
    }

    fun summaryCsv(): String {
        val header = listOf("evaluation_run_id", "detector_version", "model_name",
            "evaluation_timestamp", "scenario", "metric", "value")
        val rows = mutableListOf<List<Any?>>()
        fun add(scenario: String, metric: String, value: Any?) {
            rows += listOf(metadata.evaluationRunId, metadata.detectorVersion, metadata.modelName,
                metadata.evaluationTimestamp, scenario, metric, value)
        }
        add("THREE_WAY", "total_input_rows", records.size)
        add("THREE_WAY", "total_evaluated", evaluated.size)
        add("THREE_WAY", "total_errors", records.size - evaluated.size)
        add("THREE_WAY", "total_correct", correct)
        add("THREE_WAY", "total_incorrect", evaluated.size - correct)
        add("THREE_WAY", "exact_accuracy", format(if (evaluated.isEmpty()) null else
            correct.toDouble() / evaluated.size))
        add("THREE_WAY", "suspicious_results", suspicious)
        listOf(RiskLevel.SAFE, RiskLevel.SCAM).forEach { label ->
            RiskLevel.entries.forEach { verdict ->
                add("THREE_WAY", "actual_${label.name}_predicted_${verdict.name}", matrix(label, verdict))
            }
        }
        listOf("STRICT_SCAM_ONLY" to false, "WARNING_SCAM_OR_SUSPICIOUS" to true)
            .forEach { (scenario, warnings) ->
                val metrics = binary(warnings)
                add(scenario, "tp", metrics.tp)
                add(scenario, "fp", metrics.fp)
                add(scenario, "tn", metrics.tn)
                add(scenario, "fn", metrics.fn)
                add(scenario, "accuracy", format(metrics.accuracy))
                add(scenario, "scam_precision", format(metrics.precision))
                add(scenario, "scam_recall", format(metrics.recall))
                add(scenario, "scam_f1", format(metrics.f1))
                add(scenario, "safe_recall_specificity", format(metrics.specificity))
                add(scenario, "false_positive_rate", format(metrics.falsePositiveRate))
                add(scenario, "false_negative_rate", format(metrics.falseNegativeRate))
            }
        fun addBinary(scenario: String, metrics: BinaryMetrics) {
            add(scenario, "total_evaluated", paired.size)
            add(scenario, "tp", metrics.tp)
            add(scenario, "fp", metrics.fp)
            add(scenario, "tn", metrics.tn)
            add(scenario, "fn", metrics.fn)
            add(scenario, "total_correct", metrics.tp + metrics.tn)
            add(scenario, "total_incorrect", metrics.fp + metrics.fn)
            add(scenario, "accuracy", format(metrics.accuracy))
            add(scenario, "scam_precision", format(metrics.precision))
            add(scenario, "scam_recall", format(metrics.recall))
            add(scenario, "scam_f1", format(metrics.f1))
            add(scenario, "safe_recall_specificity", format(metrics.specificity))
            add(scenario, "false_positive_rate", format(metrics.falsePositiveRate))
            add(scenario, "false_negative_rate", format(metrics.falseNegativeRate))
        }
        addBinary("RAW_GEMMA_SCAM", rawGemmaBinary())
        addBinary("PRODUCTION_PAIRED_STRICT", productionPairedBinary(false))
        addBinary("PRODUCTION_PAIRED_WARNING", productionPairedBinary(true))
        addBinary("EVIDENCE_MODEL_STRICT", evidenceModelBinary(false))
        addBinary("EVIDENCE_MODEL_WARNING", evidenceModelBinary(true))
        addBinary("EXPERIMENTAL_FINAL_STRICT", experimentalFinalBinary(false))
        addBinary("EXPERIMENTAL_FINAL_WARNING", experimentalFinalBinary(true))
        add("RAW_GEMMA_SCAM", "suspicious_results", 0)
        add("PRODUCTION_PAIRED_THREE_WAY", "total_evaluated", paired.size)
        add("PRODUCTION_PAIRED_THREE_WAY", "total_correct", paired.count {
            it.verifiedLabel == it.finalVerdict })
        add("PRODUCTION_PAIRED_THREE_WAY", "total_incorrect", paired.count {
            it.verifiedLabel != it.finalVerdict })
        add("PRODUCTION_PAIRED_THREE_WAY", "exact_accuracy", format(if (paired.isEmpty()) null else
            paired.count { it.verifiedLabel == it.finalVerdict }.toDouble() / paired.size))
        add("PRODUCTION_PAIRED_THREE_WAY", "suspicious_results", paired.count {
            it.finalVerdict == RiskLevel.SUSPICIOUS })
        add("EVIDENCE_MODEL", "suspicious_results", paired.count {
            it.experimentalModelClassification == "SUSPICIOUS" })
        add("EXPERIMENTAL_FINAL", "suspicious_results", paired.count {
            it.experimentalFinalVerdict == RiskLevel.SUSPICIOUS })
        add("EXPERIMENTAL_FINAL_THREE_WAY", "total_evaluated", paired.size)
        add("EXPERIMENTAL_FINAL_THREE_WAY", "total_correct", paired.count {
            it.verifiedLabel == it.experimentalFinalVerdict })
        add("EXPERIMENTAL_FINAL_THREE_WAY", "total_incorrect", paired.count {
            it.verifiedLabel != it.experimentalFinalVerdict })
        add("EXPERIMENTAL_FINAL_THREE_WAY", "exact_accuracy", format(if (paired.isEmpty()) null else
            paired.count { it.verifiedLabel == it.experimentalFinalVerdict }.toDouble() / paired.size))
        listOf(RiskLevel.SAFE, RiskLevel.SCAM).forEach { label ->
            RiskLevel.entries.forEach { verdict ->
                add("PRODUCTION_PAIRED_THREE_WAY", "actual_${label.name}_predicted_${verdict.name}",
                    paired.count { it.verifiedLabel == label && it.finalVerdict == verdict })
                add("EXPERIMENTAL_FINAL_THREE_WAY", "actual_${label.name}_predicted_${verdict.name}",
                    paired.count { it.verifiedLabel == label && it.experimentalFinalVerdict == verdict })
            }
        }
        add("INFERENCE", "gemma_successes", gemmaSuccesses)
        add("INFERENCE", "gemma_failures", gemmaFailures)
        add("INFERENCE", "input_errors", records.count { it.error != null &&
            it.gemmaInferenceStatus == "NOT_RUN" })
        add("INFERENCE", "paired_comparison_rows", paired.size)
        add("INFERENCE", "average_successful_inference_ms", format(averageInferenceMs))
        add("INFERENCE", "median_successful_inference_ms", format(medianInferenceMs))
        add("INFERENCE", "minimum_successful_inference_ms", minimumInferenceMs ?: "N/A")
        add("INFERENCE", "maximum_successful_inference_ms", maximumInferenceMs ?: "N/A")
        add("INFERENCE", "p95_successful_inference_ms", p95InferenceMs ?: "N/A")
        return EvaluationCsv.encode(listOf(header) + rows)
    }

    private fun format(value: Double?): String = value?.let { String.format(Locale.US, "%.6f", it) }
        ?: "N/A"
}
