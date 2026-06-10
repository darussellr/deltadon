package com.smartwake.watch.sensing

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.smartwake.shared.model.BulkSignal
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.EPOCH_SAMPLES
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SensingCapabilities
import com.smartwake.shared.model.SensorEpoch
import com.smartwake.shared.sensing.SleepSensingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real sensing via the Samsung Health Sensor SDK, available once the AAR is in
 * watch/libs/ and Health Platform developer mode is enabled on the watch
 * (see watch/libs/README.md).
 *
 * Written against SDK 1.3.x tracker/value-key names — if your SDK version
 * differs, the compiler will flag the exact lines to fix once the AAR is
 * present. Battery note: ACCELEROMETER_CONTINUOUS + PPG_CONTINUOUS for a full
 * active window is the dominant power cost; keep the window <= 60–90 min.
 */
class SamsungSensingRepository(private val context: Context) : SleepSensingRepository {

    private companion object {
        const val TAG = "SamsungSensing"

        // Samsung-documented conversion from raw accelerometer LSB to m/s^2.
        const val ACCEL_RAW_TO_MS2 = 9.81f / (16383.75f / 4.0f)
        const val GRAVITY_MS2 = 9.81f
    }

    private var service: HealthTrackingService? = null
    private var connected = CompletableDeferred<Unit>()

    private suspend fun ensureConnected(): HealthTrackingService {
        service?.let { if (connected.isCompleted) return it }
        if (service == null) {
            connected = CompletableDeferred()
            val listener = object : ConnectionListener {
                override fun onConnectionSuccess() {
                    connected.complete(Unit)
                }

                override fun onConnectionEnded() {
                    Log.i(TAG, "Health tracking connection ended")
                }

                override fun onConnectionFailed(e: HealthTrackerException?) {
                    connected.completeExceptionally(
                        IllegalStateException("Health tracking connection failed: ${e?.errorCode}")
                    )
                }
            }
            service = HealthTrackingService(listener, context)
            service!!.connectService()
        }
        connected.await()
        return service!!
    }

    override suspend fun checkCapabilities(): SensingCapabilities {
        val svc = ensureConnected()
        val types = svc.trackingCapability.supportHealthTrackerTypes
        return SensingCapabilities(
            rawAccel = HealthTrackerType.ACCELEROMETER_CONTINUOUS in types,
            rawPpg = HealthTrackerType.PPG_CONTINUOUS in types,
            skinTemp = HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS in types,
            source = "samsung-sdk",
        )
    }

    /**
     * Bulk phase: 1/min aggregates from the continuous HR tracker plus accel
     * movement counting. The SDK has no low-rate mode for these trackers, so
     * this is the same sensors aggregated coarsely — acceptable for a first
     * pass; a duty-cycled scheme is a later battery optimization.
     */
    override fun bulkSignals(sessionId: Long, fromMillis: Long): Flow<BulkSignal> = callbackFlow {
        val svc = ensureConnected()
        val hrTracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        val accelTracker = svc.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)

        var minuteStart = System.currentTimeMillis()
        var hrSum = 0f
        var hrCount = 0
        var movementEvents = 0
        var lastMagnitude = 0f

        fun flushMinute(now: Long) {
            trySend(
                BulkSignal(
                    sessionId = sessionId,
                    timestampMillis = minuteStart,
                    movementCount = movementEvents,
                    meanHeartRateBpm = if (hrCount > 0) hrSum / hrCount else 0f,
                )
            )
            minuteStart = now
            hrSum = 0f; hrCount = 0; movementEvents = 0
        }

        val hrListener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                    if (hr > 0) {
                        hrSum += hr; hrCount++
                    }
                }
            }

            override fun onError(error: HealthTracker.TrackerError?) {
                Log.w(TAG, "HR tracker error: $error")
            }

            override fun onFlushCompleted() {}
        }

        val accelListener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) * ACCEL_RAW_TO_MS2
                    val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) * ACCEL_RAW_TO_MS2
                    val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) * ACCEL_RAW_TO_MS2
                    val magnitude = abs(sqrt(x * x + y * y + z * z) - GRAVITY_MS2) / GRAVITY_MS2
                    if (magnitude > 0.1f && lastMagnitude <= 0.1f) movementEvents++
                    lastMagnitude = magnitude
                    val now = dp.timestamp
                    if (now - minuteStart >= MINUTE_MS) flushMinute(now)
                }
            }

            override fun onError(error: HealthTracker.TrackerError?) {
                Log.w(TAG, "Accel tracker error: $error")
            }

            override fun onFlushCompleted() {}
        }

        hrTracker.setEventListener(hrListener)
        accelTracker.setEventListener(accelListener)
        awaitClose {
            hrTracker.unsetEventListener()
            accelTracker.unsetEventListener()
        }
    }

    /** Active phase: batch raw 25 Hz accel + PPG green into 30 s epochs. */
    override fun activeEpochs(sessionId: Long, fromMillis: Long): Flow<SensorEpoch> = callbackFlow {
        val svc = ensureConnected()
        val accelTracker = svc.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
        val ppgTracker = svc.getHealthTracker(HealthTrackerType.PPG_CONTINUOUS)
        val hrTracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)

        var epochStart = System.currentTimeMillis()
        val accelBuf = ArrayList<Float>(EPOCH_SAMPLES + 64)
        val ppgBuf = ArrayList<Float>(EPOCH_SAMPLES + 64)
        val ibiBuf = ArrayList<Float>(64)
        var hrSum = 0f
        var hrCount = 0

        fun flushEpoch(now: Long) {
            trySend(
                SensorEpoch(
                    sessionId = sessionId,
                    startMillis = epochStart,
                    durationMillis = EPOCH_MILLIS,
                    accelMagnitude = resample(accelBuf, EPOCH_SAMPLES),
                    ppg = resample(ppgBuf, EPOCH_SAMPLES),
                    heartRateBpm = if (hrCount > 0) hrSum / hrCount else 0f,
                    ibiMillis = ArrayList(ibiBuf),
                )
            )
            epochStart = now
            accelBuf.clear(); ppgBuf.clear(); ibiBuf.clear()
            hrSum = 0f; hrCount = 0
        }

        val accelListener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) * ACCEL_RAW_TO_MS2
                    val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) * ACCEL_RAW_TO_MS2
                    val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) * ACCEL_RAW_TO_MS2
                    accelBuf.add(abs(sqrt(x * x + y * y + z * z) - GRAVITY_MS2) / GRAVITY_MS2)
                    if (dp.timestamp - epochStart >= EPOCH_MILLIS) flushEpoch(dp.timestamp)
                }
            }

            override fun onError(error: HealthTracker.TrackerError?) {
                Log.w(TAG, "Accel tracker error: $error")
            }

            override fun onFlushCompleted() {}
        }

        val ppgListener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    ppgBuf.add(dp.getValue(ValueKey.PpgSet.PPG_GREEN).toFloat())
                }
            }

            override fun onError(error: HealthTracker.TrackerError?) {
                Log.w(TAG, "PPG tracker error: $error")
            }

            override fun onFlushCompleted() {}
        }

        val hrListener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                for (dp in dataPoints) {
                    val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                    if (hr > 0) {
                        hrSum += hr; hrCount++
                    }
                    dp.getValue(ValueKey.HeartRateSet.IBI_LIST)?.forEach { ibi ->
                        if (ibi > 0) ibiBuf.add(ibi.toFloat())
                    }
                }
            }

            override fun onError(error: HealthTracker.TrackerError?) {
                Log.w(TAG, "HR tracker error: $error")
            }

            override fun onFlushCompleted() {}
        }

        accelTracker.setEventListener(accelListener)
        ppgTracker.setEventListener(ppgListener)
        hrTracker.setEventListener(hrListener)
        awaitClose {
            accelTracker.unsetEventListener()
            ppgTracker.unsetEventListener()
            hrTracker.unsetEventListener()
        }
    }

    /** Linear resample so epochs always carry exactly [target] samples. */
    private fun resample(source: List<Float>, target: Int): FloatArray {
        if (source.isEmpty()) return FloatArray(target)
        if (source.size == target) return source.toFloatArray()
        val out = FloatArray(target)
        val scale = (source.size - 1).toDouble() / (target - 1).coerceAtLeast(1)
        for (i in 0 until target) {
            val pos = i * scale
            val lo = pos.toInt().coerceIn(0, source.size - 1)
            val hi = (lo + 1).coerceAtMost(source.size - 1)
            val frac = (pos - lo).toFloat()
            out[i] = source[lo] * (1 - frac) + source[hi] * frac
        }
        return out
    }
}
