package com.smartwake.shared.sensing

import com.smartwake.shared.model.BulkSignal
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SensingCapabilities
import com.smartwake.shared.model.SensorEpoch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Sensing source backed by a [SyntheticNightGenerator] — lets the whole pipeline
 * run with no hardware. Delays default to 0 so tests stream a full night instantly;
 * set them to real intervals to demo live behavior.
 */
class FakeSleepSensingRepository(
    private val night: SyntheticNightGenerator,
    private val delayPerBulkSignalMillis: Long = 0L,
    private val delayPerEpochMillis: Long = 0L,
) : SleepSensingRepository {

    override suspend fun checkCapabilities(): SensingCapabilities =
        SensingCapabilities(rawAccel = true, rawPpg = true, skinTemp = false, source = "synthetic")

    override fun bulkSignals(sessionId: Long, fromMillis: Long): Flow<BulkSignal> = flow {
        var t = maxOf(fromMillis, night.spec.bedTimeMillis)
        while (t < night.endMillis) {
            emit(night.bulkSignalAt(t, sessionId))
            if (delayPerBulkSignalMillis > 0) delay(delayPerBulkSignalMillis)
            t += MINUTE_MS
        }
    }

    override fun activeEpochs(sessionId: Long, fromMillis: Long): Flow<SensorEpoch> = flow {
        var t = maxOf(fromMillis, night.spec.bedTimeMillis)
        while (t < night.endMillis) {
            emit(night.epochAt(t, sessionId))
            if (delayPerEpochMillis > 0) delay(delayPerEpochMillis)
            t += EPOCH_MILLIS
        }
    }
}
