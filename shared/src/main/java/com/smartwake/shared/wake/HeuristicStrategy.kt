package com.smartwake.shared.wake

import com.smartwake.shared.features.EpochFeatures
import com.smartwake.shared.model.SleepStage
import kotlin.math.min

/**
 * Cold-start policy: prefer light sleep and movement upticks, avoid deep sleep.
 * Firing mechanics (threshold decay, deep-sleep hold, window-end fallback) are
 * shared with the learned strategy via [DecisionPolicy].
 */
class HeuristicStrategy : WakeStrategy {

    override fun evaluate(context: WakeContext): WakeDecision =
        DecisionPolicy.decide(
            score = score(context.estimatedStage, context.epochFeatures),
            estimatedStage = context.estimatedStage,
            nowMillis = context.nowMillis,
            windowStartMillis = context.windowStartMillis,
            windowEndMillis = context.windowEndMillis,
        )

    fun score(stage: SleepStage, features: EpochFeatures): Float {
        val base = when (stage) {
            SleepStage.AWAKE -> 0.90f
            SleepStage.LIGHT -> 0.70f
            SleepStage.REM -> 0.45f
            SleepStage.DEEP -> 0.05f
        }
        val movementBonus = min(features.movementPeakCount * 0.03f, 0.20f)
        return min(base + movementBonus, 1f)
    }
}
