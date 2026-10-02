package com.apcida.smishingdetector.backend.gemma

import android.content.Context

object GemmaManager {

    @Volatile
    private var validator: GemmaValidator? = null

    fun getValidator(context: Context): GemmaValidator {
        return validator ?: synchronized(this) {
            validator ?: GemmaValidator(context.applicationContext).also {
                validator = it
            }
        }
    }

    suspend fun loadModel(context: Context): Boolean = getValidator(context).loadModel()

    fun release() {
        synchronized(this) {
            validator?.release()
            // Keep the shared validator so existing SMS controllers use the same model lock.
        }
    }
}
