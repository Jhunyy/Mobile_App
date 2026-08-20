package com.apcida.smishingdetector.model.data

import com.apcida.smishingdetector.model.entity.Keyword

data class DetectionResult(

    // Stage 1 outputs
    val messageContent: String,
    val riskScore: Float,
    val riskLevel: RiskLevel,
    val isFlagged: Boolean,
    val matchedKeywords: List<Keyword>,

    // Stage 2 outputs (nullable — only populated if Gemma was invoked)
    val gemmaInvoked: Boolean = false,
    val gemmaResult: GemmaResult? = null,

    // Final combined verdict
    val finalClassification: String,   // "SAFE", "SCAM", or "LEGITIMATE"
    val finalRationale: String?        // Gemma rationale or null if not invoked
)