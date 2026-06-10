package com.smartwake.shared.features

import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SensorEpoch
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.StageSegment
import kotlin.math.abs
import kotlin.math.sqrt

/** Nightly tabular features for the macro (Dense) model head. */
data class MacroFeatures(
    val totalSleepMin: Float,
    val deepMin: Float,
    val remMin: Float,
    val lightMin: Float,
    val awakeningCount: Int,
) {
    fun toFloatArray(): FloatArray =
        floatArrayOf(totalSleepMin, deepMin, remMin, lightMin, awakeningCount.toFloat())
}

/** Per-epoch summary features used by the heuristic strategy and as auxiliary model inputs. */
data class EpochFeatures(
    val movementRms: Float,
    val movementPeakCount: Int,
    val heartRateBpm: Float,
    val rmssdMillis: Float,
) {
    fun toFloatArray(): FloatArray =
        floatArrayOf(movementRms, movementPeakCount.toFloat(), heartRateBpm, rmssdMillis)
}

object FeatureExtraction {

    const val PEAK_THRESHOLD_G = 0.1f
    const val PEAK_REFRACTORY_SAMPLES = 12 // ~0.5 s at 25 Hz

    fun macroFeatures(hypnogram: List<StageSegment>): MacroFeatures {
        var light = 0L
        var deep = 0L
        var rem = 0L
        for (segment in hypnogram) {
            when (segment.stage) {
                SleepStage.LIGHT -> light += segment.durationMillis
                SleepStage.DEEP -> deep += segment.durationMillis
                SleepStage.REM -> rem += segment.durationMillis
                SleepStage.AWAKE -> {}
            }
        }
        val onset = hypnogram.firstOrNull { it.stage != SleepStage.AWAKE }?.startMillis
        val awakenings = if (onset == null) 0 else {
            hypnogram.count { it.stage == SleepStage.AWAKE && it.startMillis > onset }
        }
        return MacroFeatures(
            totalSleepMin = (light + deep + rem).toFloat() / MINUTE_MS,
            deepMin = deep.toFloat() / MINUTE_MS,
            remMin = rem.toFloat() / MINUTE_MS,
            lightMin = light.toFloat() / MINUTE_MS,
            awakeningCount = awakenings,
        )
    }

    fun epochFeatures(epoch: SensorEpoch): EpochFeatures {
        val accel = epoch.accelMagnitude
        var sumSquares = 0.0
        for (v in accel) sumSquares += v.toDouble() * v
        val rms = sqrt(sumSquares / accel.size).toFloat()

        var peaks = 0
        var i = 0
        while (i < accel.size) {
            if (abs(accel[i]) > PEAK_THRESHOLD_G) {
                peaks++
                i += PEAK_REFRACTORY_SAMPLES
            } else {
                i++
            }
        }
        return EpochFeatures(
            movementRms = rms,
            movementPeakCount = peaks,
            heartRateBpm = epoch.heartRateBpm,
            rmssdMillis = rmssd(epoch.ibiMillis),
        )
    }

    /** Root mean square of successive IBI differences — short-window HRV. */
    fun rmssd(ibiMillis: List<Float>): Float {
        if (ibiMillis.size < 2) return 0f
        var sum = 0.0
        for (k in 1 until ibiMillis.size) {
            val d = (ibiMillis[k] - ibiMillis[k - 1]).toDouble()
            sum += d * d
        }
        return sqrt(sum / (ibiMillis.size - 1)).toFloat()
    }

    /** 750×2 tensor for the micro (Conv1D→GRU) head: channel 0 accel, channel 1 PPG. */
    fun microTensor(epoch: SensorEpoch): Array<FloatArray> =
        Array(epoch.accelMagnitude.size) { i ->
            floatArrayOf(epoch.accelMagnitude[i], epoch.ppg[i])
        }
}
