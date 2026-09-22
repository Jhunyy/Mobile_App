package com.apcida.smishingdetector.backend.gemma

import android.content.Context
import android.util.Log
import com.apcida.smishingdetector.model.data.GemmaResult
import com.apcida.smishingdetector.util.Constants
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class GemmaValidator(private val context: Context) {

    companion object {
        private const val TAG = "GemmaValidator"
        private const val MODEL_FILE_NAME = "gemma3-1b-it-int4.task"
    }

    private var llmInference: LlmInference? = null
    private var isModelLoaded = false
    private val inferenceMutex = Mutex()

    /**
     * Detects if the app is running on an Android emulator.
     * Gemma via MediaPipe requires a physical device.
     */
    private fun isEmulator(): Boolean {
        return (android.os.Build.FINGERPRINT.startsWith("generic")
                || android.os.Build.FINGERPRINT.startsWith("unknown")
                || android.os.Build.MODEL.contains("google_sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86")
                || android.os.Build.MANUFACTURER.contains("Genymotion")
                || android.os.Build.BRAND.startsWith("generic")
                || android.os.Build.DEVICE.startsWith("generic"))
    }

    /**
     * Validates a flagged SMS message using Gemma.
     *
     * Builds a structured prompt from the message and
     * detected keywords, runs inference, then parses
     * the output into a GemmaResult.
     *
     * Returns a fallback result if the model is not loaded
     * or if inference fails.
     */
    suspend fun validate(
        messageBody: String,
        matchedKeywords: List<String>
    ): GemmaResult {
        return withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                try {
                    if (!isModelLoaded || llmInference == null) {
                        Log.w(TAG, "Gemma model not loaded. Returning fallback result.")
                        return@withLock GemmaResult.fallback()
                    }

                    // Build structured prompt
                    val prompt = PromptBuilder.build(messageBody, matchedKeywords)
                    Log.d(TAG, "Prompt built. Running Gemma inference...")

                    // Run inference
                    val rawOutput = llmInference!!.generateResponse(prompt)

                    // Strict parsing validates supported labels, confidence,
                    // rationale presence, and rejects all additional output.
                    GemmaOutputParser.parse(rawOutput)

                } catch (e: Exception) {
                    Log.e(TAG, "Gemma inference failed: ${e.message}")
                    GemmaResult.fallback()
                }
            }
        }
    }

    /**
     * Returns the preferred local file path for the Gemma model.
     */
    private fun getModelFile(): File {
        // Primary location — internal app storage (after first copy)
        val internalFile = File(context.filesDir, MODEL_FILE_NAME)
        if (internalFile.exists()) return internalFile

        // Secondary location — where adb push placed the model
        val adbFile = File("/data/local/tmp/llm/$MODEL_FILE_NAME")
        if (adbFile.exists()) return adbFile

        return internalFile
    }
    /**
     * Loads the Gemma 3 1B INT4 model from internal storage or the adb push path.
     * Model loading is heavy, so this runs on the IO dispatcher.
     */
    suspend fun loadModel() {
        withContext(Dispatchers.IO) {
            try {
                if (isModelLoaded && llmInference != null) {
                    Log.d(TAG, "Gemma model is already loaded.")
                    return@withContext
                }

                if (isEmulator()) {
                    Log.w(TAG, "Emulator detected. Skipping Gemma.")
                    isModelLoaded = false
                    return@withContext
                }

                Log.d(TAG, "Loading Gemma model...")
                val modelFile = getModelFile()

                if (!modelFile.exists()) {
                    // Try copying from adb push location
                    val adbFile = File("/data/local/tmp/llm/$MODEL_FILE_NAME")
                    if (adbFile.exists()) {
                        Log.d(TAG, "Found model in /data/local/tmp/llm/. Copying...")
                        copyModelFile(adbFile, modelFile)
                    } else {
                        Log.e(TAG, "Model file not found on device.")
                        isModelLoaded = false
                        return@withContext
                    }
                }

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(Constants.GEMMA_MAX_TOKENS)
                    //.setTemperature(Constants.GEMMA_TEMPERATURE)
                    //.setTopK(40)
                    //.setRandomSeed(42)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                isModelLoaded = true
                Log.d(TAG, "Gemma model loaded successfully.")

            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "MediaPipe native library not found. Requires physical device.")
                isModelLoaded = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Gemma model: ${e.message}")
                isModelLoaded = false
            }
        }
    }
    private fun copyModelFile(source: File, destination: File) {
        try {
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            Log.d(TAG, "Model copied to: ${destination.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model: ${e.message}")
        }
    }

    /**
     * Releases the Gemma model from memory.
     * Call this when the app is destroyed or when
     * the model is no longer needed.
     */
    fun release() {
        llmInference?.close()
        llmInference = null
        isModelLoaded = false
        Log.d(TAG, "Gemma model released from memory.")
    }

    /**
     * Returns whether the model is currently loaded and ready.
     */
    fun isReady(): Boolean = isModelLoaded
}
