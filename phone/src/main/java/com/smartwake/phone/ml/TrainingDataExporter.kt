package com.smartwake.phone.ml

import android.content.Context
import com.smartwake.phone.data.PhoneServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dumps labeled examples as JSON for training/train_base_model.py
 * (--personalize mode). Pull the file with:
 *   adb pull /sdcard/Android/data/com.smartwake/files/training_examples.json
 */
object TrainingDataExporter {

    suspend fun export(context: Context): File? {
        val examples = PhoneServices.db(context).trainingExampleDao().labeled()
        if (examples.isEmpty()) return null

        val array = JSONArray()
        for (e in examples) {
            array.put(
                JSONObject().apply {
                    put("firedAt", e.fireTimestampMillis)
                    put("mode", e.mode)
                    put("score", e.score)
                    put("tags", e.tags ?: "")
                    put("responseLatencyMillis", e.responseLatencyMillis ?: -1L)
                    put("macro", JSONArray(e.macro.map { it.toDouble() }))
                    put("accel", JSONArray(e.accel.map { it.toDouble() }))
                    put("ppg", JSONArray(e.ppg.map { it.toDouble() }))
                }
            )
        }

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "training_examples.json")
        file.writeText(array.toString())
        return file
    }
}
