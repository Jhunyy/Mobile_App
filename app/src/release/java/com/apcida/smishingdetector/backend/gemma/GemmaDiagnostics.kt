package com.apcida.smishingdetector.backend.gemma

import com.apcida.smishingdetector.model.data.GemmaResult

/** No raw model output is written to production logs. */
internal object GemmaDiagnostics {
    fun logOutput(attempt: String, raw: String, parsed: GemmaResult) = Unit
}
