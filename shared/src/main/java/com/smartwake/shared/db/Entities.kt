package com.smartwake.shared.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_session")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val onsetEstimateMillis: Long? = null,
    val endMillis: Long? = null,
    /** UsageMode name: PASSIVE, SMART_WINDOW, RESEARCH. */
    val mode: String,
    val windowStartMillis: Long? = null,
    val windowEndMillis: Long? = null,
)

@Suppress("ArrayInDataClass")
@Entity(
    tableName = "sensor_sample",
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMillis: Long,
    /** e.g. EPOCH_ACCEL, EPOCH_PPG, HR, IBI, MOVEMENT_COUNT, SKIN_TEMP. */
    val type: String,
    val sampleValues: FloatArray,
    /** SensingPhase name: BULK or ACTIVE. */
    val phase: String,
)

@Entity(
    tableName = "stage_event",
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class StageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMillis: Long,
    /** SleepStage name. */
    val stage: String,
    /** e.g. heuristic, samsung-sdk, synthetic. */
    val source: String,
)

@Suppress("ArrayInDataClass")
@Entity(
    tableName = "wake_event",
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class WakeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val fireTimestampMillis: Long,
    /** UsageMode name at fire time. */
    val mode: String,
    /** Serialized model input at the fired moment (macro + epoch summary features). */
    val featureVector: FloatArray,
)

@Entity(
    tableName = "energy_label",
    foreignKeys = [ForeignKey(
        entity = WakeEventEntity::class,
        parentColumns = ["id"],
        childColumns = ["wakeEventId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("wakeEventId")],
)
data class EnergyLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wakeEventId: Long,
    /** Subjective morning energy, 1–10. */
    val score: Int,
    /** Comma-joined tags, e.g. "groggy,headache". */
    val tags: String? = null,
    /** Time from alarm fire to score entry — a behavioral alertness proxy. */
    val responseLatencyMillis: Long? = null,
)

@Entity(tableName = "model_meta")
data class ModelMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val version: String,
    val trainedAtMillis: Long,
    val sampleCount: Int,
    val metricsJson: String? = null,
)

/**
 * Self-contained supervised example: full model inputs at the fired moment plus
 * the (eventually) reported energy score. Created at fire time with a null
 * score, completed when the user logs energy. On the phone these rows arrive
 * via the Data Layer and double as the history view, so no foreign keys.
 */
@Suppress("ArrayInDataClass")
@Entity(tableName = "training_example")
data class TrainingExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fireTimestampMillis: Long,
    /** UsageMode name at fire time. */
    val mode: String,
    /** Macro head input, 5 floats. */
    val macro: FloatArray,
    /** Micro head input channels at 25 Hz, 750 floats each. */
    val accel: FloatArray,
    val ppg: FloatArray,
    /** Subjective energy 1–10; null until the user logs it. */
    val score: Int? = null,
    val tags: String? = null,
    val responseLatencyMillis: Long? = null,
)
