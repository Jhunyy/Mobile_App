package com.apcida.smishingdetector.backend.gemma

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GemmaManager {

    @Volatile
    private var validator: GemmaValidator? = null

    private val modelLoadMutex = Mutex()

    fun getValidator(context: Context): GemmaValidator {
        return validator ?: synchronized(this) {
            validator ?: GemmaValidator(context.applicationContext).also {
                validator = it
            }
        }
    }

    suspend fun loadModel(context: Context) {
        getReadyValidator(context)
    }

    /**
     * Returns the shared validator after attempting an idempotent model load.
     * This makes SMS processing independent from MainActivity having been
     * opened and prevents concurrent workers from loading multiple copies.
     */
    suspend fun getReadyValidator(context: Context): GemmaValidator {
        val sharedValidator = getValidator(context)

        if (!sharedValidator.isReady()) {
            modelLoadMutex.withLock {
                if (!sharedValidator.isReady()) {
                    sharedValidator.loadModel()
                }
            }
        }

        return sharedValidator
    }

    fun release() {
        synchronized(this) {
            validator?.release()
            validator = null
        }
    }
}
