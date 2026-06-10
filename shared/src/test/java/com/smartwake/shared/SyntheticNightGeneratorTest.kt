package com.smartwake.shared

import com.smartwake.shared.model.EPOCH_SAMPLES
import com.smartwake.shared.model.HOUR_MS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.sensing.SyntheticNightGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class SyntheticNightGeneratorTest {

    private fun night(seed: Long = 42L) = SyntheticNightGenerator(
        SyntheticNightGenerator.NightSpec(bedTimeMillis = 0L, seed = seed)
    )

    @Test
    fun hypnogramIsContiguousAndCoversNight() {
        val n = night()
        assertEquals(0L, n.hypnogram.first().startMillis)
        assertEquals(n.endMillis, n.hypnogram.last().endMillis)
        n.hypnogram.zipWithNext().forEach { (a, b) ->
            assertEquals("gap between segments", a.endMillis, b.startMillis)
        }
    }

    @Test
    fun deepSleepIsFrontLoadedAndRemIsBackLoaded() {
        // Architectural shape should hold across seeds, not just one lucky night.
        for (seed in 1L..5L) {
            val n = night(seed)
            val mid = n.endMillis / 2
            fun minutesOf(stage: SleepStage, from: Long, to: Long): Long = n.hypnogram
                .filter { it.stage == stage }
                .sumOf { max(0L, min(it.endMillis, to) - max(it.startMillis, from)) } / MINUTE_MS

            assertTrue(
                "deep not front-loaded for seed $seed",
                minutesOf(SleepStage.DEEP, 0, mid) > minutesOf(SleepStage.DEEP, mid, n.endMillis)
            )
            assertTrue(
                "REM not back-loaded for seed $seed",
                minutesOf(SleepStage.REM, mid, n.endMillis) > minutesOf(SleepStage.REM, 0, mid)
            )
        }
    }

    @Test
    fun epochsAreDeterministicWithCorrectShape() {
        val n = night()
        val t = n.sleepOnsetMillis + 2 * HOUR_MS
        val a = n.epochAt(t)
        val b = n.epochAt(t)
        assertEquals(EPOCH_SAMPLES, a.accelMagnitude.size)
        assertEquals(EPOCH_SAMPLES, a.ppg.size)
        assertArrayEquals(a.accelMagnitude, b.accelMagnitude, 0f)
        assertArrayEquals(a.ppg, b.ppg, 0f)
        assertTrue(a.ibiMillis.isNotEmpty())
        assertTrue("HR out of range: ${a.heartRateBpm}", a.heartRateBpm in 40f..90f)
    }

    @Test
    fun wakingFromDeepScoresWorseThanWakingFromLateLightSleep() {
        val n = night()
        val deepSegment = n.hypnogram.first { it.stage == SleepStage.DEEP }
        val lateLightSegment = n.hypnogram.last { it.stage == SleepStage.LIGHT }
        val deepEnergy = n.groundTruthEnergy((deepSegment.startMillis + deepSegment.endMillis) / 2)
        val lightEnergy = n.groundTruthEnergy((lateLightSegment.startMillis + lateLightSegment.endMillis) / 2)
        assertTrue(
            "expected light-wake energy ($lightEnergy) > deep-wake energy ($deepEnergy)",
            lightEnergy > deepEnergy
        )
    }
}
