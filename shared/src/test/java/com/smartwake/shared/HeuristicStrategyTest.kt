package com.smartwake.shared

import com.smartwake.shared.features.EpochFeatures
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.UsageMode
import com.smartwake.shared.wake.HeuristicStrategy
import com.smartwake.shared.wake.WakeContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicStrategyTest {

    private val strategy = HeuristicStrategy()
    private val windowEnd = 60 * MINUTE_MS
    private val quiet = EpochFeatures(movementRms = 0.005f, movementPeakCount = 0, heartRateBpm = 52f, rmssdMillis = 60f)
    private val moving = EpochFeatures(movementRms = 0.04f, movementPeakCount = 6, heartRateBpm = 58f, rmssdMillis = 40f)

    private fun ctx(stage: SleepStage, now: Long, features: EpochFeatures = quiet) = WakeContext(
        nowMillis = now,
        windowStartMillis = 0L,
        windowEndMillis = windowEnd,
        mode = UsageMode.SMART_WINDOW,
        estimatedStage = stage,
        epochFeatures = features,
    )

    @Test
    fun holdsDuringDeepSleepMidWindow() {
        assertFalse(strategy.evaluate(ctx(SleepStage.DEEP, now = 20 * MINUTE_MS)).fire)
    }

    @Test
    fun firesOnLightSleepWithMovementUptick() {
        assertTrue(strategy.evaluate(ctx(SleepStage.LIGHT, now = 1 * MINUTE_MS, features = moving)).fire)
    }

    @Test
    fun quietLightSleepWaitsThenFiresAsThresholdDecays() {
        assertFalse(strategy.evaluate(ctx(SleepStage.LIGHT, now = 5 * MINUTE_MS)).fire)
        assertTrue(strategy.evaluate(ctx(SleepStage.LIGHT, now = 20 * MINUTE_MS)).fire)
    }

    @Test
    fun alwaysFiresAtWindowEndEvenFromDeepSleep() {
        assertTrue(strategy.evaluate(ctx(SleepStage.DEEP, now = windowEnd - EPOCH_MILLIS)).fire)
    }

    @Test
    fun scoreOrderingPrefersLightOverRemOverDeep() {
        val light = strategy.score(SleepStage.LIGHT, quiet)
        val rem = strategy.score(SleepStage.REM, quiet)
        val deep = strategy.score(SleepStage.DEEP, quiet)
        assertTrue(light > rem)
        assertTrue(rem > deep)
    }
}
