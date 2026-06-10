package com.smartwake.shared.features

import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.StageSegment

/**
 * Builds nightly macro features from a sequence of per-epoch (or per-minute)
 * stage estimates as they accumulate during a live session. Each point spans
 * until the next point; consecutive identical stages are merged into segments.
 *
 * During the bulk phase the stages are coarse accelerometer/HR proxies
 * (AWAKE vs LIGHT), so the resulting macro features are proxies too — exactly
 * the degradation the brief anticipates for accelerometer-derived inputs.
 */
object MacroFeatureBuilder {

    fun fromStagePoints(points: List<Pair<Long, SleepStage>>, endMillis: Long): MacroFeatures {
        if (points.isEmpty()) return MacroFeatures(0f, 0f, 0f, 0f, 0)
        val sorted = points.sortedBy { it.first }
        val segments = mutableListOf<StageSegment>()
        var currentStage = sorted.first().second
        var currentStart = sorted.first().first
        for (i in 1 until sorted.size) {
            val (t, stage) = sorted[i]
            if (stage != currentStage) {
                segments += StageSegment(currentStage, currentStart, t)
                currentStage = stage
                currentStart = t
            }
        }
        segments += StageSegment(currentStage, currentStart, maxOf(endMillis, currentStart))
        return FeatureExtraction.macroFeatures(segments)
    }
}
