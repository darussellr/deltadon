package com.smartwake.shared.sensing

import com.smartwake.shared.model.BulkSignal
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.EPOCH_SAMPLES
import com.smartwake.shared.model.HOUR_MS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SensorEpoch
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.StageSegment
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a physiologically plausible synthetic night: a hypnogram with ~90 min
 * cycles (deep sleep front-loaded, REM back-loaded), plus 25 Hz accel/PPG epochs,
 * coarse bulk signals, and a ground-truth wake-energy function for labeling.
 *
 * All per-timestamp outputs are pure functions of (seed, time), so they are
 * reproducible regardless of call order.
 */
class SyntheticNightGenerator(val spec: NightSpec) {

    data class NightSpec(
        val bedTimeMillis: Long,
        val timeInBedMillis: Long = 8 * HOUR_MS,
        val seed: Long = 42L,
        val cycleLengthMillis: Long = 90 * MINUTE_MS,
        val sleepLatencyMillis: Long = 12 * MINUTE_MS,
    )

    val endMillis: Long = spec.bedTimeMillis + spec.timeInBedMillis
    val sleepOnsetMillis: Long = spec.bedTimeMillis + spec.sleepLatencyMillis
    val hypnogram: List<StageSegment> = buildHypnogram()

    fun stageAt(timeMillis: Long): SleepStage =
        hypnogram.firstOrNull { timeMillis in it }?.stage ?: SleepStage.AWAKE

    fun bulkSignalAt(timeMillis: Long, sessionId: Long = 0L): BulkSignal {
        val p = profile(stageAt(timeMillis))
        val rng = rngFor(17L, timeMillis)
        val movement = p.movementPerMin.first +
            rng.nextInt(p.movementPerMin.last - p.movementPerMin.first + 1)
        val hr = p.hrBpm + rng.nextGaussian().toFloat() * p.hrSd
        return BulkSignal(sessionId, timeMillis, movement, hr)
    }

    fun epochAt(startMillis: Long, sessionId: Long = 0L): SensorEpoch {
        val stage = stageAt(startMillis)
        val p = profile(stage)
        val rng = rngFor(31L, startMillis)

        val accel = FloatArray(EPOCH_SAMPLES) { (rng.nextGaussian() * p.accelSigma).toFloat() }
        if (rng.nextDouble() < p.burstProb) {
            val burstLen = (25.0 * (0.8 + rng.nextDouble() * 2.2)).toInt() // 0.8–3 s of movement
            val start = rng.nextInt(EPOCH_SAMPLES - burstLen)
            for (i in start until start + burstLen) {
                accel[i] += (rng.nextGaussian() * p.burstAmp).toFloat()
            }
        }

        // Beat sequence covering the epoch; successive-difference jitter sets RMSSD.
        val baseIbi = 60_000f / (p.hrBpm + rng.nextGaussian().toFloat() * p.hrSd)
        val ibis = mutableListOf<Float>()
        var covered = 0f
        while (covered < EPOCH_MILLIS) {
            val ibi = (baseIbi + rng.nextGaussian().toFloat() * p.ibiJitterMs).coerceIn(400f, 1500f)
            ibis += ibi
            covered += ibi
        }

        // PPG: pulse waveform driven by the beat sequence + respiration wander + noise.
        val ppg = FloatArray(EPOCH_SAMPLES)
        var beatIdx = 0
        var beatStart = 0f
        for (i in 0 until EPOCH_SAMPLES) {
            val tMs = i * (1000f / 25f)
            while (beatIdx < ibis.size - 1 && tMs >= beatStart + ibis[beatIdx]) {
                beatStart += ibis[beatIdx]
                beatIdx++
            }
            val phase = ((tMs - beatStart) / ibis[beatIdx]).coerceIn(0f, 1f)
            ppg[i] = pulseWave(phase) +
                0.05f * rng.nextGaussian().toFloat() +
                0.08f * sin(2.0 * PI * 0.25 * (tMs / 1000.0)).toFloat()
        }

        val meanIbi = ibis.average().toFloat()
        return SensorEpoch(
            sessionId = sessionId,
            startMillis = startMillis,
            durationMillis = EPOCH_MILLIS,
            accelMagnitude = accel,
            ppg = ppg,
            heartRateBpm = 60_000f / meanIbi,
            ibiMillis = ibis,
        )
    }

    /**
     * Ground-truth subjective energy (1–10) if the sleeper is woken at [wakeTimeMillis].
     * Used to label synthetic training examples and to validate wake strategies.
     */
    fun groundTruthEnergy(wakeTimeMillis: Long): Float {
        val stage = stageAt(wakeTimeMillis)
        val rng = rngFor(13L, wakeTimeMillis)

        var energy = when (stage) {
            SleepStage.AWAKE -> 7.6
            SleepStage.LIGHT -> 7.2
            SleepStage.REM -> 5.6
            SleepStage.DEEP -> 3.2
        }

        // Waking from deeper into a deep segment is worse (more inertia).
        if (stage == SleepStage.DEEP) {
            val segment = hypnogram.first { wakeTimeMillis in it }
            val minutesIn = (wakeTimeMillis - segment.startMillis).toDouble() / MINUTE_MS
            energy -= min(minutesIn * 0.08, 1.0)
        }

        // Recovering time since the last deep segment ended helps.
        val lastDeepEnd = hypnogram
            .lastOrNull { it.stage == SleepStage.DEEP && it.endMillis <= wakeTimeMillis }
            ?.endMillis
        if (lastDeepEnd != null) {
            energy += min((wakeTimeMillis - lastDeepEnd).toDouble() / (30 * MINUTE_MS), 1.0) * 0.7
        }

        // Total sleep accumulated by wake time, vs a 7 h reference.
        val sleptMin = hypnogram
            .filter { it.stage != SleepStage.AWAKE }
            .sumOf { (min(it.endMillis, wakeTimeMillis) - it.startMillis).coerceAtLeast(0L) }
            .toDouble() / MINUTE_MS
        energy += ((sleptMin - 420.0) / 60.0 * 0.5).coerceIn(-1.5, 0.5)

        energy += rng.nextGaussian() * 0.5
        return energy.coerceIn(1.0, 10.0).toFloat()
    }

    private fun buildHypnogram(): List<StageSegment> {
        val rng = Random(spec.seed)
        val segments = mutableListOf<StageSegment>()
        var t = spec.bedTimeMillis

        fun add(stage: SleepStage, durationMillis: Long) {
            if (t >= endMillis || durationMillis <= 0) return
            val end = min(t + durationMillis, endMillis)
            segments += StageSegment(stage, t, end)
            t = end
        }

        add(SleepStage.AWAKE, spec.sleepLatencyMillis)
        var cycle = 0
        while (t < endMillis) {
            val scale = spec.cycleLengthMillis.toDouble() / (90 * MINUTE_MS)
            add(SleepStage.LIGHT, (rng.nextLong(8, 16) * MINUTE_MS * scale).toLong())
            val deepBase = (38 * MINUTE_MS * 0.55.pow(cycle) * scale).toLong()
            if (deepBase > 4 * MINUTE_MS) add(SleepStage.DEEP, jitter(rng, deepBase, 0.2))
            add(SleepStage.LIGHT, (rng.nextLong(6, 13) * MINUTE_MS * scale).toLong())
            val remBase = (min(9 + 7L * cycle, 32L) * MINUTE_MS * scale).toLong()
            add(SleepStage.REM, jitter(rng, remBase, 0.25))
            if (rng.nextDouble() < 0.35) add(SleepStage.AWAKE, rng.nextLong(1, 4) * MINUTE_MS)
            cycle++
        }
        return segments
    }

    private fun jitter(rng: Random, base: Long, fraction: Double): Long =
        (base * (1.0 + (rng.nextDouble() * 2 - 1) * fraction)).toLong()

    /** SplitMix-style hash so nearby timestamps get decorrelated PRNG streams. */
    private fun rngFor(salt: Long, timeMillis: Long): java.util.Random {
        var x = spec.seed * 1_000_003L + salt * 31L + timeMillis / 1000L
        x = x xor (x ushr 33)
        x *= 0x9E3779B97F4A7C15uL.toLong()
        x = x xor (x ushr 29)
        return java.util.Random(x)
    }

    private fun pulseWave(phase: Float): Float {
        val systolic = exp(-((phase - 0.18f).pow(2)) / 0.0018f)
        val dicrotic = 0.35f * exp(-((phase - 0.48f).pow(2)) / 0.0098f)
        return systolic + dicrotic
    }

    private data class StageProfile(
        val accelSigma: Float,
        val burstProb: Double,
        val burstAmp: Float,
        val hrBpm: Float,
        val hrSd: Float,
        val ibiJitterMs: Float,
        val movementPerMin: IntRange,
    )

    private fun profile(stage: SleepStage): StageProfile = when (stage) {
        SleepStage.AWAKE -> StageProfile(0.08f, 0.90, 0.50f, 68f, 3.0f, 25f, 8..20)
        SleepStage.LIGHT -> StageProfile(0.015f, 0.12, 0.15f, 58f, 2.0f, 30f, 0..3)
        SleepStage.DEEP -> StageProfile(0.006f, 0.02, 0.10f, 51f, 1.5f, 45f, 0..1)
        SleepStage.REM -> StageProfile(0.012f, 0.10, 0.12f, 64f, 4.0f, 15f, 0..2)
    }
}
