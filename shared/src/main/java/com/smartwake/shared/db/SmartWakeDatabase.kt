package com.smartwake.shared.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SleepSessionEntity::class,
        SensorSampleEntity::class,
        StageEventEntity::class,
        WakeEventEntity::class,
        EnergyLabelEntity::class,
        ModelMetaEntity::class,
        TrainingExampleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SmartWakeDatabase : RoomDatabase() {

    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun sensorSampleDao(): SensorSampleDao
    abstract fun stageEventDao(): StageEventDao
    abstract fun wakeEventDao(): WakeEventDao
    abstract fun energyLabelDao(): EnergyLabelDao
    abstract fun modelMetaDao(): ModelMetaDao
    abstract fun trainingExampleDao(): TrainingExampleDao

    companion object {
        fun build(context: Context): SmartWakeDatabase =
            Room.databaseBuilder(context, SmartWakeDatabase::class.java, "smartwake.db").build()
    }
}
