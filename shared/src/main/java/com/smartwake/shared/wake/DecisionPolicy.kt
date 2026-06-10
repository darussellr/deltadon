package com.smartwake.shared.wake

import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SleepStage
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shared firing policy used by both the heuristic and the learned strategy:
 * the fire threshold decays linearly to zero across the window (guaranteeing a
 * fire before window end), deep sleep is held until the final margin, and an
 * optional epsilon-greedy exploration kicks in only when the score sits in the
 * uncertain band around the threshold (contextual-bandit-style exploration at
 * model-uncertain moments, not uniform randomization).
 */
object DecisionPolicy {

    const val THRESHOLD_AT_WINDOW_START = 0.85f
    const val UNCERTAINTY_BAND = 0.10f
    val DEEP_HOLD_MARGIN_MS = 5 * MINUTE_MS

    fun decide(
        score: Float,
        estimatedStage: SleepStage,
        nowMillis: Long,
        windowStartMillis: Long,
        windowEndMillis: Long,
        exploreEpsilon: Float = 0f,
        random: Random = Random(nowMillis),
    ): WakeDecision {
        val remaining = windowEndMillis - nowMillis
        if (remaining <= EPOCH_MILLIS) {
            return WakeDecision(true, score, "window end fallback")
        }
        if (estimatedStage == SleepStage.DEEP && remaining > DEEP_HOLD_MARGIN_MS) {
            return WakeDecision(false, score, "deep sleep, holding")
        }

        val span = (windowEndMillis - windowStartMillis).toFloat()
        val progress = ((nowMillis - windowStartMillis) / span).coerceIn(0f, 1f)
        val threshold = THRESHOLD_AT_WINDOW_START * (1f - progress)

        if (exploreEpsilon > 0f &&
            abs(score - threshold) < UNCERTAINTY_BAND &&
            random.nextFloat() < exploreEpsilon
        ) {
            return WakeDecision(random.nextBoolean(), score, "explore (uncertain band)")
        }

        return WakeDecision(
            fire = score >= threshold,
            score = score,
            reason = "score=%.2f threshold=%.2f stage=%s".format(score, threshold, estimatedStage),
        )
    }
}
