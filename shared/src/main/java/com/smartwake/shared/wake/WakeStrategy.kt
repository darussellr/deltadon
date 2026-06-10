package com.smartwake.shared.wake

import com.smartwake.shared.features.EpochFeatures
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.UsageMode

data class WakeContext(
    val nowMillis: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val mode: UsageMode,
    val estimatedStage: SleepStage,
    val epochFeatures: EpochFeatures,
)

data class WakeDecision(
    val fire: Boolean,
    val score: Float,
    val reason: String,
)

/**
 * Pluggable wake-moment policy, evaluated once per 30 s epoch inside the window.
 * Implementations: [HeuristicStrategy] (cold start) and a TFLite-backed
 * LearnedStrategy (later milestone). Callers must still enforce a hard
 * fallback fire at window end regardless of what the strategy returns.
 */
interface WakeStrategy {
    fun evaluate(context: WakeContext): WakeDecision
}
