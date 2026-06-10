package com.smartwake.phone.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs the two-headed TFLite model (macro 5-vector + micro 750×2 tensor →
 * predicted energy 1–10). The model file is produced by training/
 * train_base_model.py and pushed to filesDir/models/smartwake.tflite.
 *
 * All interpreter access is guarded by a ReentrantLock — TFLite interpreters
 * are not thread-safe and epochs arrive every 30 s on binder threads.
 */
class PhoneInferenceEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "PhoneInference"
        const val MODEL_DIR = "models"
        const val MODEL_FILE = "smartwake.tflite"

        @Volatile
        private var instance: PhoneInferenceEngine? = null

        fun get(context: Context): PhoneInferenceEngine =
            instance ?: synchronized(this) {
                instance ?: PhoneInferenceEngine(context.applicationContext).also { instance = it }
            }
    }

    private val modelFile = File(File(context.filesDir, MODEL_DIR), MODEL_FILE)
    private val lock = ReentrantLock()
    private var interpreter: Interpreter? = null
    private var loadedModelTimestamp = 0L

    fun isModelAvailable(): Boolean = modelFile.exists()

    fun modelFilePath(): String = modelFile.absolutePath

    /**
     * Returns predicted wake quality normalized to 0..1 (energy 1–10 mapped
     * linearly), or null when no model is installed — callers treat null as
     * "stay silent, let the watch heuristic decide".
     */
    fun scoreEpoch(macro: FloatArray, accel: FloatArray, ppg: FloatArray): Float? = lock.withLock {
        val interp = loadInterpreterLocked() ?: return null
        if (accel.size != ppg.size || accel.isEmpty()) return null
        try {
            val micro = Array(1) { Array(accel.size) { i -> floatArrayOf(accel[i], ppg[i]) } }
            val macroIn = arrayOf(macro)
            val output = Array(1) { FloatArray(1) }

            // Match inputs by rank: the 3-D tensor is the micro head.
            val microFirst = interp.getInputTensor(0).shape().size == 3
            val inputs: Array<Any> = if (microFirst) arrayOf(micro, macroIn) else arrayOf(macroIn, micro)
            interp.runForMultipleInputsOutputs(inputs, mapOf(0 to output))

            val energy = output[0][0].coerceIn(1f, 10f)
            (energy - 1f) / 9f
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            null
        }
    }

    private fun loadInterpreterLocked(): Interpreter? {
        if (!modelFile.exists()) return null
        // Hot-reload when a newer model file is dropped in.
        if (interpreter == null || modelFile.lastModified() != loadedModelTimestamp) {
            interpreter?.close()
            interpreter = try {
                Interpreter(modelFile).also { loadedModelTimestamp = modelFile.lastModified() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                null
            }
        }
        return interpreter
    }
}
