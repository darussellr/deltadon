package com.smartwake.shared.model

const val MINUTE_MS: Long = 60_000L
const val HOUR_MS: Long = 3_600_000L

/** Active-phase epoch geometry: 30 s windows sampled at 25 Hz. */
const val EPOCH_MILLIS: Long = 30_000L
const val SAMPLE_RATE_HZ: Int = 25
const val EPOCH_SAMPLES: Int = (EPOCH_MILLIS / 1000L).toInt() * SAMPLE_RATE_HZ // 750

enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

enum class UsageMode { PASSIVE, SMART_WINDOW, RESEARCH }

enum class SensingPhase { BULK, ACTIVE }

data class StageSegment(
    val stage: SleepStage,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = endMillis - startMillis

    operator fun contains(timeMillis: Long): Boolean =
        timeMillis in startMillis until endMillis
}

/**
 * One 30 s window of high-rate sensor data captured during the active phase.
 *
 * On Galaxy Watch the 25 Hz physiological channel is raw PPG (HR from the SDK is
 * processed ~1 Hz), so [ppg] is the raw waveform and [heartRateBpm]/[ibiMillis]
 * are the derived/processed values for the same window.
 */
class SensorEpoch(
    val sessionId: Long,
    val startMillis: Long,
    val durationMillis: Long = EPOCH_MILLIS,
    /** Gravity-removed acceleration magnitude in g, [EPOCH_SAMPLES] values at 25 Hz. */
    val accelMagnitude: FloatArray,
    /** Raw PPG waveform (arbitrary units), [EPOCH_SAMPLES] values at 25 Hz. */
    val ppg: FloatArray,
    val heartRateBpm: Float,
    /** Inter-beat intervals (ms) for beats inside this window. */
    val ibiMillis: List<Float>,
    val skinTempCelsius: Float? = null,
)

/** Coarse low-power signal emitted ~1/min during the bulk phase. */
data class BulkSignal(
    val sessionId: Long,
    val timestampMillis: Long,
    /** Movement events (accel threshold crossings) in the last minute. */
    val movementCount: Int,
    val meanHeartRateBpm: Float,
)

data class SensingCapabilities(
    val rawAccel: Boolean,
    val rawPpg: Boolean,
    val skinTemp: Boolean,
    /** Human-readable provider, e.g. "samsung-sdk", "health-services", "synthetic". */
    val source: String,
)
