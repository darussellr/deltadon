package com.smartwake.shared

import com.smartwake.shared.features.FeatureExtraction
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.EPOCH_SAMPLES
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SensorEpoch
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.StageSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class FeatureExtractionTest {

    @Test
    fun macroFeaturesComputedFromHypnogram() {
        val segments = listOf(
            StageSegment(SleepStage.AWAKE, 0, 10 * MINUTE_MS),
            StageSegment(SleepStage.LIGHT, 10 * MINUTE_MS, 70 * MINUTE_MS),
            StageSegment(SleepStage.DEEP, 70 * MINUTE_MS, 100 * MINUTE_MS),
            StageSegment(SleepStage.AWAKE, 100 * MINUTE_MS, 102 * MINUTE_MS),
            StageSegment(SleepStage.REM, 102 * MINUTE_MS, 122 * MINUTE_MS),
        )
        val m = FeatureExtraction.macroFeatures(segments)
        assertEquals(110f, m.totalSleepMin, 1e-4f)
        assertEquals(30f, m.deepMin, 1e-4f)
        assertEquals(20f, m.remMin, 1e-4f)
        assertEquals(60f, m.lightMin, 1e-4f)
        assertEquals(1, m.awakeningCount)
        assertArrayEquals(floatArrayOf(110f, 30f, 20f, 60f, 1f), m.toFloatArray(), 1e-4f)
    }

    @Test
    fun rmssdMatchesHandComputedValue() {
        // diffs are +10 and -20 -> sqrt((100 + 400) / 2)
        assertEquals(sqrt(250.0).toFloat(), FeatureExtraction.rmssd(listOf(800f, 810f, 790f)), 1e-3f)
        assertEquals(0f, FeatureExtraction.rmssd(listOf(800f)), 0f)
        assertEquals(0f, FeatureExtraction.rmssd(emptyList()), 0f)
    }

    @Test
    fun epochFeaturesComputeRmsAndPeaks() {
        val epoch = SensorEpoch(
            sessionId = 0,
            startMillis = 0,
            durationMillis = EPOCH_MILLIS,
            accelMagnitude = FloatArray(EPOCH_SAMPLES) { 0.2f },
            ppg = FloatArray(EPOCH_SAMPLES),
            heartRateBpm = 60f,
            ibiMillis = listOf(1000f, 1000f),
        )
        val f = FeatureExtraction.epochFeatures(epoch)
        assertEquals(0.2f, f.movementRms, 1e-4f)
        // every sample exceeds the threshold; refractory of 12 -> hits at 0, 12, ..., 744
        assertEquals(63, f.movementPeakCount)
        assertEquals(60f, f.heartRateBpm, 0f)
        assertEquals(0f, f.rmssdMillis, 0f)
    }

    @Test
    fun microTensorMapsChannels() {
        val epoch = SensorEpoch(
            sessionId = 0,
            startMillis = 0,
            durationMillis = EPOCH_MILLIS,
            accelMagnitude = FloatArray(EPOCH_SAMPLES) { 0.2f },
            ppg = FloatArray(EPOCH_SAMPLES) { it.toFloat() },
            heartRateBpm = 60f,
            ibiMillis = listOf(1000f, 1000f),
        )
        val tensor = FeatureExtraction.microTensor(epoch)
        assertEquals(EPOCH_SAMPLES, tensor.size)
        assertEquals(2, tensor[0].size)
        assertEquals(0.2f, tensor[5][0], 0f)
        assertEquals(5f, tensor[5][1], 0f)
    }
}
