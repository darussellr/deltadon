package com.smartwake.shared.link

import com.smartwake.shared.model.SensorEpoch
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.UsageMode
import java.nio.ByteBuffer

/** Message paths on the Wearable Data Layer. */
object WirePaths {
    const val EPOCH = "/smartwake/epoch"
    const val DECISION = "/smartwake/decision"
    const val LABEL = "/smartwake/label"
}

/** One active-phase epoch plus the context the phone needs to score it. */
class ActiveEpochPacket(
    val sessionId: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val mode: UsageMode,
    val estimatedStage: SleepStage,
    /** Current macro-feature snapshot (5 floats). */
    val macro: FloatArray,
    val epoch: SensorEpoch,
)

/** Phone's verdict for one epoch, returned to the watch. */
class DecisionPacket(
    val epochStartMillis: Long,
    val fire: Boolean,
    val score: Float,
)

/** A labeled wake event (with the fired epoch's raw signals) for training. */
class LabelPacket(
    val fireTimestampMillis: Long,
    val mode: UsageMode,
    val macro: FloatArray,
    val accel: FloatArray,
    val ppg: FloatArray,
    val score: Int,
    val tags: String,
    val responseLatencyMillis: Long,
)

/**
 * Hand-rolled binary encoding for Data Layer messages. An epoch packet is
 * ~6 KB (2×750 floats), well under MessageClient limits.
 */
object EpochCodec {

    private const val VERSION: Byte = 1

    fun encodeEpoch(p: ActiveEpochPacket): ByteArray {
        val e = p.epoch
        val size = 1 + 8 * 4 + 1 + 1 +
            4 + p.macro.size * 4 +
            8 + 4 + // epoch start, hr
            4 + e.ibiMillis.size * 4 +
            4 + e.accelMagnitude.size * 4 +
            4 + e.ppg.size * 4
        val buf = ByteBuffer.allocate(size)
        buf.put(VERSION)
        buf.putLong(p.sessionId)
        buf.putLong(p.windowStartMillis)
        buf.putLong(p.windowEndMillis)
        buf.putLong(e.startMillis)
        buf.put(p.mode.ordinal.toByte())
        buf.put(p.estimatedStage.ordinal.toByte())
        putFloats(buf, p.macro)
        buf.putFloat(e.heartRateBpm)
        buf.putInt(e.ibiMillis.size)
        for (v in e.ibiMillis) buf.putFloat(v)
        putFloats(buf, e.accelMagnitude)
        putFloats(buf, e.ppg)
        return buf.array()
    }

    fun decodeEpoch(data: ByteArray): ActiveEpochPacket {
        val buf = ByteBuffer.wrap(data)
        require(buf.get() == VERSION) { "unsupported epoch packet version" }
        val sessionId = buf.long
        val windowStart = buf.long
        val windowEnd = buf.long
        val epochStart = buf.long
        val mode = UsageMode.entries[buf.get().toInt()]
        val stage = SleepStage.entries[buf.get().toInt()]
        val macro = getFloats(buf)
        val hr = buf.float
        val ibiCount = buf.int
        val ibis = ArrayList<Float>(ibiCount)
        repeat(ibiCount) { ibis.add(buf.float) }
        val accel = getFloats(buf)
        val ppg = getFloats(buf)
        return ActiveEpochPacket(
            sessionId = sessionId,
            windowStartMillis = windowStart,
            windowEndMillis = windowEnd,
            mode = mode,
            estimatedStage = stage,
            macro = macro,
            epoch = SensorEpoch(
                sessionId = sessionId,
                startMillis = epochStart,
                accelMagnitude = accel,
                ppg = ppg,
                heartRateBpm = hr,
                ibiMillis = ibis,
            ),
        )
    }

    fun encodeDecision(p: DecisionPacket): ByteArray {
        val buf = ByteBuffer.allocate(1 + 8 + 1 + 4)
        buf.put(VERSION)
        buf.putLong(p.epochStartMillis)
        buf.put(if (p.fire) 1 else 0)
        buf.putFloat(p.score)
        return buf.array()
    }

    fun decodeDecision(data: ByteArray): DecisionPacket {
        val buf = ByteBuffer.wrap(data)
        require(buf.get() == VERSION) { "unsupported decision packet version" }
        return DecisionPacket(buf.long, buf.get() == 1.toByte(), buf.float)
    }

    fun encodeLabel(p: LabelPacket): ByteArray {
        val tagBytes = p.tags.toByteArray(Charsets.UTF_8)
        val size = 1 + 8 + 1 +
            4 + p.macro.size * 4 +
            4 + p.accel.size * 4 +
            4 + p.ppg.size * 4 +
            4 + 4 + tagBytes.size + 8
        val buf = ByteBuffer.allocate(size)
        buf.put(VERSION)
        buf.putLong(p.fireTimestampMillis)
        buf.put(p.mode.ordinal.toByte())
        putFloats(buf, p.macro)
        putFloats(buf, p.accel)
        putFloats(buf, p.ppg)
        buf.putInt(p.score)
        buf.putInt(tagBytes.size)
        buf.put(tagBytes)
        buf.putLong(p.responseLatencyMillis)
        return buf.array()
    }

    fun decodeLabel(data: ByteArray): LabelPacket {
        val buf = ByteBuffer.wrap(data)
        require(buf.get() == VERSION) { "unsupported label packet version" }
        val firedAt = buf.long
        val mode = UsageMode.entries[buf.get().toInt()]
        val macro = getFloats(buf)
        val accel = getFloats(buf)
        val ppg = getFloats(buf)
        val score = buf.int
        val tagLen = buf.int
        val tagBytes = ByteArray(tagLen)
        buf.get(tagBytes)
        val latency = buf.long
        return LabelPacket(firedAt, mode, macro, accel, ppg, score, String(tagBytes, Charsets.UTF_8), latency)
    }

    private fun putFloats(buf: ByteBuffer, values: FloatArray) {
        buf.putInt(values.size)
        for (v in values) buf.putFloat(v)
    }

    private fun getFloats(buf: ByteBuffer): FloatArray {
        val n = buf.int
        return FloatArray(n) { buf.float }
    }
}
