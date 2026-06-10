package com.smartwake.shared

import com.smartwake.shared.features.MacroFeatureBuilder
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroFeatureBuilderTest {

    @Test
    fun buildsSegmentsFromStagePoints() {
        // One point per minute: 10 awake, 30 light, 20 deep, 5 awake, 15 rem.
        val points = mutableListOf<Pair<Long, SleepStage>>()
        var t = 0L
        fun emit(stage: SleepStage, minutes: Int) = repeat(minutes) {
            points += t to stage
            t += MINUTE_MS
        }
        emit(SleepStage.AWAKE, 10)
        emit(SleepStage.LIGHT, 30)
        emit(SleepStage.DEEP, 20)
        emit(SleepStage.AWAKE, 5)
        emit(SleepStage.REM, 15)

        val m = MacroFeatureBuilder.fromStagePoints(points, endMillis = t)
        assertEquals(65f, m.totalSleepMin, 1e-4f)
        assertEquals(30f, m.lightMin, 1e-4f)
        assertEquals(20f, m.deepMin, 1e-4f)
        assertEquals(15f, m.remMin, 1e-4f)
        assertEquals(1, m.awakeningCount)
    }

    @Test
    fun emptyPointsYieldZeroFeatures() {
        val m = MacroFeatureBuilder.fromStagePoints(emptyList(), endMillis = 0L)
        assertEquals(0f, m.totalSleepMin, 0f)
        assertEquals(0, m.awakeningCount)
    }
}
