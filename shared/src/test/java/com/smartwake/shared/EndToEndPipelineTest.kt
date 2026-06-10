package com.smartwake.shared

import com.smartwake.shared.features.FeatureExtraction
import com.smartwake.shared.model.EPOCH_SAMPLES
import com.smartwake.shared.model.HOUR_MS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.UsageMode
import com.smartwake.shared.sensing.FakeSleepSensingRepository
import com.smartwake.shared.sensing.SyntheticNightGenerator
import com.smartwake.shared.wake.HeuristicStrategy
import com.smartwake.shared.wake.OnlineStageEstimator
import com.smartwake.shared.wake.WakeContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streams synthetic nights through the full pipeline — fake repo -> feature
 * extraction -> stage estimation -> heuristic wake decision — and checks the
 * fired wake-ups land inside the window and mostly avoid deep sleep.
 */
class EndToEndPipelineTest {

    @Test
    fun pipelineFiresInsideWindowAndAvoidsDeepSleep() = runTest {
        val windowStart = 6 * HOUR_MS + 30 * MINUTE_MS
        val windowEnd = 7 * HOUR_MS + 30 * MINUTE_MS
        var deepFires = 0
        val seeds = 1L..10L

        for (seed in seeds) {
            val night = SyntheticNightGenerator(
                SyntheticNightGenerator.NightSpec(bedTimeMillis = 0L, seed = seed)
            )
            val repo = FakeSleepSensingRepository(night)
            val estimator = OnlineStageEstimator()
            val strategy = HeuristicStrategy()

            val fired = repo.activeEpochs(sessionId = 1L, fromMillis = windowStart)
                .takeWhile { it.startMillis < windowEnd }
                .map { epoch ->
                    assertEquals(EPOCH_SAMPLES, epoch.accelMagnitude.size)
                    val features = FeatureExtraction.epochFeatures(epoch)
                    val stage = estimator.update(features)
                    epoch.startMillis to strategy.evaluate(
                        WakeContext(
                            nowMillis = epoch.startMillis,
                            windowStartMillis = windowStart,
                            windowEndMillis = windowEnd,
                            mode = UsageMode.SMART_WINDOW,
                            estimatedStage = stage,
                            epochFeatures = features,
                        )
                    )
                }
                .firstOrNull { (_, decision) -> decision.fire }

            // Hard fallback: if the strategy never fired, the alarm fires at window end.
            val fireTime = fired?.first ?: windowEnd
            assertTrue("seed $seed fired outside window", fireTime in windowStart..windowEnd)
            if (night.stageAt(fireTime) == SleepStage.DEEP) deepFires++
        }

        assertTrue("fired during deep sleep on $deepFires/10 nights", deepFires <= 2)
    }

    @Test
    fun bulkSignalsCoverTheNightAtOnePerMinute() = runTest {
        val night = SyntheticNightGenerator(
            SyntheticNightGenerator.NightSpec(bedTimeMillis = 0L, seed = 7L)
        )
        val repo = FakeSleepSensingRepository(night)
        var count = 0
        var lastTimestamp = -1L
        repo.bulkSignals(sessionId = 1L, fromMillis = 0L).collect {
            if (lastTimestamp >= 0) assertEquals(MINUTE_MS, it.timestampMillis - lastTimestamp)
            lastTimestamp = it.timestampMillis
            count++
        }
        assertEquals((night.endMillis / MINUTE_MS).toInt(), count)
    }
}
