package com.smartwake.shared.sensing

import com.smartwake.shared.model.BulkSignal
import com.smartwake.shared.model.SensingCapabilities
import com.smartwake.shared.model.SensorEpoch
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the sensing source. Implementations:
 *  - Samsung Health Sensor SDK (watch, developer mode) — primary path
 *  - Jetpack Health Services — optional coarse fallback
 *  - [FakeSleepSensingRepository] — synthetic nights, no hardware required
 */
interface SleepSensingRepository {

    suspend fun checkCapabilities(): SensingCapabilities

    /** Low-power coarse signals, ~1/min, for the bulk phase (first hours of the night). */
    fun bulkSignals(sessionId: Long, fromMillis: Long): Flow<BulkSignal>

    /** High-rate 30 s epochs (25 Hz accel + PPG) for the active phase inside the wake window. */
    fun activeEpochs(sessionId: Long, fromMillis: Long): Flow<SensorEpoch>
}
