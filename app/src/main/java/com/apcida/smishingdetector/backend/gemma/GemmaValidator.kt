package com.apcida.smishingdetector.backend.gemma

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.util.Constants
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GemmaValidator(private val context: Context) {

    companion object {
        private const val TAG = "GemmaValidator"
        private const val MODEL_FILE_NAME = "gemma3-1b-it-int4.task"
    }

    private val modelLock = Any()

    private var llmInference: LlmInference? = null
    private var isModelLoaded = false

    /**
     * Validates every SMS message using Gemma, loading the model on demand.
     *
     * Builds a structured prompt from the message and
     * detected keywords, runs inference, then parses
     * the output into a GemmaResult.
     *
     * Returns a fallback result if the model cannot be loaded
     * or if inference fails.
     */
    suspend fun validate(
        messageBody: String,
        matchedKeywords: List<String>
    ): GemmaResult {
        return withContext(Dispatchers.IO) {
            synchronized(modelLock) {
                try {
                    loadModelLocked()
                    if (llmInference == null) {
                        Log.w(TAG, "Gemma model not loaded. Returning fallback result.")
                        return@synchronized GemmaResult.fallback()
                    }

                    // Build structured prompt
                    val prompt = PromptBuilder.build(messageBody, matchedKeywords)
                    Log.d(TAG, "Prompt built. Running Gemma inference...")

                    // Run inference
                    val rawOutput = llmInference!!.generateResponse(prompt)
                    Log.d(TAG, "Gemma raw output: $rawOutput")

                    // Validate output format
                    if (!GemmaOutputParser.isValidFormat(rawOutput)) {
                        Log.w(TAG, "Gemma output format is invalid. Returning fallback.")
                        return@synchronized GemmaResult.fallback()
                    }

                    // Parse and return structured result
                    GemmaOutputParser.parse(rawOutput)

                } catch (e: Exception) {
                    Log.e(TAG, "Gemma inference failed", e)
                    GemmaResult.fallback()
                }
            }
        }
    }

    /**
     * Returns the preferred local file path for the Gemma model.
     */
    private fun getModelFile(): File? {
        // The model can be installed into app storage or pushed to the device with adb.
        val internalFile = File(context.filesDir, MODEL_FILE_NAME)
        if (internalFile.isFile && internalFile.canRead()) return internalFile

        val adbFile = File("/data/local/tmp/llm/$MODEL_FILE_NAME")
        if (adbFile.isFile && adbFile.canRead()) return adbFile

        return null
    }

    /**
     * Loads the Gemma 3 1B INT4 model from internal storage or the adb push path.
     * Model loading is heavy, so this runs on the IO dispatcher.
     */
    suspend fun loadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            synchronized(modelLock) {
                loadModelLocked()
                isModelLoaded
            }
        }
    }

    // Loading, inference, and release share a lock so overlapping SMS cannot
    // use or close the same native model at the same time.
    private fun loadModelLocked() {
        try {
            if (isModelLoaded && llmInference != null) {
                Log.d(TAG, "Gemma model is already loaded.")
                return
            }

            Log.d(TAG, "Loading Gemma model...")
            val modelFile = getModelFile()

            if (modelFile == null) {
                Log.e(TAG, "Readable model not found. Expected $MODEL_FILE_NAME in ${context.filesDir} or /data/local/tmp/llm/")
                isModelLoaded = false
                return
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)

                // MediaPipe counts prompt and response tokens together.
                .setMaxTokens(Constants.GEMMA_MAX_TOKENS)

                // CPU is generally the safer compatibility test on different Android GPUs.
                .setPreferredBackend(LlmInference.Backend.CPU)

                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isModelLoaded = true
            Log.d(TAG, "Gemma model loaded successfully.")

        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "MediaPipe native library could not be loaded", e)
            isModelLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Gemma model", e)
            isModelLoaded = false
        }
    }

    /**
     * Releases the Gemma model from memory.
     * Call this when the app is destroyed or when
     * the model is no longer needed.
     */
    fun release(): Unit = synchronized(modelLock) {
        llmInference?.close()
        llmInference = null
        isModelLoaded = false
        Log.d(TAG, "Gemma model released from memory.")
    }

    /**
     * Returns whether the model is currently loaded and ready.
     */
    fun isReady(): Boolean = synchronized(modelLock) { isModelLoaded }
}
