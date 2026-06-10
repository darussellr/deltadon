package com.smartwake.shared.wake

import com.smartwake.shared.features.EpochFeatures
import com.smartwake.shared.model.SleepStage

/**
 * Rule-based per-epoch stage estimate with majority smoothing over the last few
 * epochs. Thresholds: movement marks wake; low HR + high RMSSD marks deep sleep
 * (parasympathetic dominance); elevated HR + low RMSSD with atonia marks REM.
 */
class OnlineStageEstimator(private val smoothingWindow: Int = 5) {

    private val recent = ArrayDeque<SleepStage>()

    fun update(features: EpochFeatures): SleepStage {
        recent.addLast(classify(features))
        while (recent.size > smoothingWindow) recent.removeFirst()
        return recent.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }

    fun reset() = recent.clear()

    private fun classify(f: EpochFeatures): SleepStage = when {
        f.movementRms > 0.06f -> SleepStage.AWAKE
        f.heartRateBpm < 55f && f.rmssdMillis > 52f -> SleepStage.DEEP
        f.heartRateBpm > 59f && f.rmssdMillis < 28f -> SleepStage.REM
        else -> SleepStage.LIGHT
    }
}
