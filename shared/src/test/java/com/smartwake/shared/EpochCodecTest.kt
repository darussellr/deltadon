package com.smartwake.shared

import com.smartwake.shared.link.ActiveEpochPacket
import com.smartwake.shared.link.DecisionPacket
import com.smartwake.shared.link.EpochCodec
import com.smartwake.shared.link.LabelPacket
import com.smartwake.shared.model.EPOCH_SAMPLES
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.UsageMode
import com.smartwake.shared.sensing.SyntheticNightGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EpochCodecTest {

    @Test
    fun epochPacketRoundTrips() {
        val night = SyntheticNightGenerator(SyntheticNightGenerator.NightSpec(bedTimeMillis = 0L))
        val epoch = night.epochAt(night.sleepOnsetMillis + 3_600_000L, sessionId = 9L)
        val packet = ActiveEpochPacket(
            sessionId = 9L,
            windowStartMillis = 100L,
            windowEndMillis = 200L,
            mode = UsageMode.SMART_WINDOW,
            estimatedStage = SleepStage.LIGHT,
            macro = floatArrayOf(400f, 60f, 90f, 250f, 2f),
            epoch = epoch,
        )

        val decoded = EpochCodec.decodeEpoch(EpochCodec.encodeEpoch(packet))

        assertEquals(packet.sessionId, decoded.sessionId)
        assertEquals(packet.windowStartMillis, decoded.windowStartMillis)
        assertEquals(packet.windowEndMillis, decoded.windowEndMillis)
        assertEquals(packet.mode, decoded.mode)
        assertEquals(packet.estimatedStage, decoded.estimatedStage)
        assertArrayEquals(packet.macro, decoded.macro, 0f)
        assertEquals(epoch.startMillis, decoded.epoch.startMillis)
        assertEquals(epoch.heartRateBpm, decoded.epoch.heartRateBpm, 0f)
        assertEquals(epoch.ibiMillis, decoded.epoch.ibiMillis)
        assertEquals(EPOCH_SAMPLES, decoded.epoch.accelMagnitude.size)
        assertArrayEquals(epoch.accelMagnitude, decoded.epoch.accelMagnitude, 0f)
        assertArrayEquals(epoch.ppg, decoded.epoch.ppg, 0f)
    }

    @Test
    fun decisionPacketRoundTrips() {
        val decoded = EpochCodec.decodeDecision(
            EpochCodec.encodeDecision(DecisionPacket(epochStartMillis = 123L, fire = true, score = 0.73f))
        )
        assertEquals(123L, decoded.epochStartMillis)
        assertEquals(true, decoded.fire)
        assertEquals(0.73f, decoded.score, 0f)
    }

    @Test
    fun labelPacketRoundTrips() {
        val packet = LabelPacket(
            fireTimestampMillis = 555L,
            mode = UsageMode.RESEARCH,
            macro = floatArrayOf(1f, 2f, 3f, 4f, 5f),
            accel = FloatArray(EPOCH_SAMPLES) { it * 0.01f },
            ppg = FloatArray(EPOCH_SAMPLES) { it * 0.02f },
            score = 7,
            tags = "groggy,headache",
            responseLatencyMillis = 4200L,
        )
        val decoded = EpochCodec.decodeLabel(EpochCodec.encodeLabel(packet))
        assertEquals(packet.fireTimestampMillis, decoded.fireTimestampMillis)
        assertEquals(packet.mode, decoded.mode)
        assertArrayEquals(packet.macro, decoded.macro, 0f)
        assertArrayEquals(packet.accel, decoded.accel, 0f)
        assertArrayEquals(packet.ppg, decoded.ppg, 0f)
        assertEquals(packet.score, decoded.score)
        assertEquals(packet.tags, decoded.tags)
        assertEquals(packet.responseLatencyMillis, decoded.responseLatencyMillis)
    }
}
