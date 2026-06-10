package com.smartwake.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SleepSessionDao {
    @Insert
    suspend fun insert(session: SleepSessionEntity): Long

    @Update
    suspend fun update(session: SleepSessionEntity)

    @Query("SELECT * FROM sleep_session WHERE id = :id")
    suspend fun byId(id: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_session ORDER BY startMillis DESC")
    suspend fun all(): List<SleepSessionEntity>
}

@Dao
interface SensorSampleDao {
    @Insert
    suspend fun insertAll(samples: List<SensorSampleEntity>)

    @Query("SELECT * FROM sensor_sample WHERE sessionId = :sessionId ORDER BY timestampMillis")
    suspend fun forSession(sessionId: Long): List<SensorSampleEntity>
}

@Dao
interface StageEventDao {
    @Insert
    suspend fun insert(event: StageEventEntity): Long

    @Query("SELECT * FROM stage_event WHERE sessionId = :sessionId ORDER BY timestampMillis")
    suspend fun forSession(sessionId: Long): List<StageEventEntity>
}

@Dao
interface WakeEventDao {
    @Insert
    suspend fun insert(event: WakeEventEntity): Long

    @Query("SELECT * FROM wake_event WHERE sessionId = :sessionId ORDER BY fireTimestampMillis")
    suspend fun forSession(sessionId: Long): List<WakeEventEntity>
}

/** A wake event joined with its energy label — one supervised training example. */
@Suppress("ArrayInDataClass")
data class LabeledExample(
    val featureVector: FloatArray,
    val score: Int,
)

@Dao
interface EnergyLabelDao {
    @Insert
    suspend fun insert(label: EnergyLabelEntity): Long

    @Query("SELECT * FROM energy_label")
    suspend fun all(): List<EnergyLabelEntity>

    @Query(
        "SELECT w.featureVector AS featureVector, l.score AS score " +
            "FROM wake_event w JOIN energy_label l ON l.wakeEventId = w.id"
    )
    suspend fun labeledExamples(): List<LabeledExample>
}

@Dao
interface ModelMetaDao {
    @Insert
    suspend fun insert(meta: ModelMetaEntity): Long

    @Query("SELECT * FROM model_meta ORDER BY trainedAtMillis DESC LIMIT 1")
    suspend fun latest(): ModelMetaEntity?
}

@Dao
interface TrainingExampleDao {
    @Insert
    suspend fun insert(example: TrainingExampleEntity): Long

    @Query("UPDATE training_example SET score = :score, tags = :tags, responseLatencyMillis = :latencyMillis WHERE id = :id")
    suspend fun applyLabel(id: Long, score: Int, tags: String?, latencyMillis: Long?)

    @Query("SELECT * FROM training_example ORDER BY fireTimestampMillis DESC")
    suspend fun all(): List<TrainingExampleEntity>

    @Query("SELECT * FROM training_example WHERE score IS NOT NULL ORDER BY fireTimestampMillis")
    suspend fun labeled(): List<TrainingExampleEntity>
}
