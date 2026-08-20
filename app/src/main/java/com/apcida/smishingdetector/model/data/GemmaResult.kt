package com.apcida.smishingdetector.model.data

data class GemmaResult(
    val classification: String,   // "SCAM" or "LEGITIMATE"
    val confidence: String,       // "HIGH", "MEDIUM", or "LOW"
    val rationale: String,        // one-sentence plain-language explanation
    val isSuccessful: Boolean = true,
    val errorMessage: String? = null
) {
    companion object {
        // Returns a fallback result when Gemma fails or times out
        fun fallback(): GemmaResult {
            return GemmaResult(
                classification = "UNCERTAIN",
                confidence = "LOW",
                rationale = "Contextual analysis was unavailable. Classification based on keyword scoring only.",
                isSuccessful = false,
                errorMessage = "Gemma inference failed or timed out."
            )
        }
    }
}