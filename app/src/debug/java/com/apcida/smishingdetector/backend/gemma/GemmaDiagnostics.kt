package com.apcida.smishingdetector.backend.gemma

import android.util.Log
import com.apcida.smishingdetector.model.data.GemmaResult

/** Debug builds only: raw output may quote private SMS text. */
internal object GemmaDiagnostics {
    fun logOutput(attempt: String, raw: String, parsed: GemmaResult) {
        Log.d("GemmaRawDebug", "attempt=$attempt raw=$raw")
        Log.d("GemmaRawDebug", "attempt=$attempt parsed=${parsed.classification} " +
            "confidence=${parsed.confidence} reason=${parsed.rationale} valid=${parsed.isSuccessful}")
    }
}
