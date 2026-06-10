package com.smartwake.watch.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.smartwake.shared.db.SensorSampleEntity
import com.smartwake.shared.db.SleepSessionEntity
import com.smartwake.shared.db.StageEventEntity
import com.smartwake.shared.db.TrainingExampleEntity
import com.smartwake.shared.db.WakeEventEntity
import com.smartwake.shared.features.EpochFeatures
import com.smartwake.shared.features.FeatureExtraction
import com.smartwake.shared.features.MacroFeatureBuilder
import com.smartwake.shared.link.ActiveEpochPacket
import com.smartwake.shared.link.DecisionPacket
import com.smartwake.shared.link.EpochCodec
import com.smartwake.shared.link.WirePaths
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.SensingPhase
import com.smartwake.shared.model.SensorEpoch
import com.smartwake.shared.model.SleepStage
import com.smartwake.shared.model.UsageMode
import com.smartwake.shared.sensing.SleepSensingRepository
import com.smartwake.shared.wake.HeuristicStrategy
import com.smartwake.shared.wake.OnlineStageEstimator
import com.smartwake.shared.wake.WakeContext
import com.smartwake.shared.wake.WakeDecision
import com.smartwake.watch.MainActivity
import com.smartwake.watch.SmartWakeApp
import com.smartwake.watch.alarm.AlarmActivity
import com.smartwake.watch.alarm.AlarmScheduler
import com.smartwake.watch.data.WatchServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Owns a whole night: bulk-phase coarse tracking from "start sleep", ramp to
 * the 25 Hz active phase at window start, per-epoch wake decisions (local
 * heuristic, overridden by fresher phone verdicts when the Data Layer
 * delivers them), the alarm itself, and session teardown after labeling.
 */
class SleepSessionService : Service() {

    companion object {
        private const val TAG = "SleepSessionService"
        const val ACTION_START_SESSION = "com.smartwake.watch.START_SESSION"
        const val ACTION_ENTER_ACTIVE = "com.smartwake.watch.ENTER_ACTIVE"
        const val ACTION_FALLBACK_FIRE = "com.smartwake.watch.FALLBACK_FIRE"
        const val ACTION_SILENCE = "com.smartwake.watch.SILENCE"
        const val ACTION_COMPLETE = "com.smartwake.watch.COMPLETE"

        const val EXTRA_WAKE_EVENT_ID = "wakeEventId"
        const val EXTRA_EXAMPLE_ID = "exampleId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_FIRE_TIME = "fireTime"

        private const val NOTIF_TRACKING = 1
        private const val NOTIF_ALARM = 2

        /** Phone decisions older than this are stale; fall back to local. */
        private const val PHONE_DECISION_TTL_MS = 2 * EPOCH_MILLIS

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bulkJob: Job? = null
    private var activeJob: Job? = null

    private var repo: SleepSensingRepository? = null
    private var sessionId = -1L
    private var windowStartMillis = 0L
    private var windowEndMillis = 0L
    private var mode = UsageMode.PASSIVE
    private var researchTargetStage: SleepStage? = null

    private val estimator = OnlineStageEstimator()
    private val strategy = HeuristicStrategy()
    private val stageHistory = mutableListOf<Pair<Long, SleepStage>>()
    private var lastEpoch: SensorEpoch? = null
    private var lastFeatures: EpochFeatures? = null
    private var lastStage: SleepStage = SleepStage.AWAKE

    @Volatile
    private var fired = false

    @Volatile
    private var phoneDecision: DecisionPacket? = null
    private var phoneDecisionReceivedAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    private val decisionListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == WirePaths.DECISION) {
            phoneDecision = EpochCodec.decodeDecision(event.data)
            phoneDecisionReceivedAt = System.currentTimeMillis()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Wearable.getMessageClient(this).addListener(decisionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> startSession()
            ACTION_ENTER_ACTIVE -> enterActivePhase()
            ACTION_FALLBACK_FIRE -> fire("window end fallback alarm")
            ACTION_SILENCE -> silenceAlarm()
            ACTION_COMPLETE -> completeSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(decisionListener)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    // ---- session phases -------------------------------------------------

    private fun startSession() {
        if (isRunning) return
        isRunning = true
        fired = false
        stageHistory.clear()
        estimator.reset()

        val now = System.currentTimeMillis()
        val schedule = WatchServices.config.computeSchedule(now)
        windowStartMillis = schedule.windowStartMillis
        windowEndMillis = schedule.windowEndMillis
        mode = schedule.mode
        researchTargetStage = if (mode == UsageMode.RESEARCH) {
            // Stratified target: LIGHT, DEEP or REM, rotated per session start.
            SleepStage.entries[1 + Random(now).nextInt(3)]
        } else null

        startForeground(trackingNotification("Tracking sleep — alarm by ${fmt(windowEndMillis)}"))
        repo = WatchServices.sensingRepository(bedTimeMillis = now)

        AlarmScheduler.scheduleEnterActive(this, windowStartMillis)
        AlarmScheduler.scheduleFallbackFire(this, windowEndMillis)

        scope.launch {
            sessionId = WatchServices.db.sleepSessionDao().insert(
                SleepSessionEntity(
                    startMillis = now,
                    mode = mode.name,
                    windowStartMillis = windowStartMillis,
                    windowEndMillis = windowEndMillis,
                )
            )
            startBulkPhase(now)
        }
    }

    private fun startBulkPhase(fromMillis: Long) {
        val repository = repo ?: return
        bulkJob = scope.launch {
            repository.bulkSignals(sessionId, fromMillis).collect { signal ->
                val db = WatchServices.db
                db.sensorSampleDao().insertAll(
                    listOf(
                        SensorSampleEntity(
                            sessionId = sessionId,
                            timestampMillis = signal.timestampMillis,
                            type = "MOVEMENT_HR",
                            sampleValues = floatArrayOf(signal.movementCount.toFloat(), signal.meanHeartRateBpm),
                            phase = SensingPhase.BULK.name,
                        )
                    )
                )
                // Coarse stage proxy: enough for macro-feature accumulation.
                val stage = if (signal.movementCount > 4) SleepStage.AWAKE else SleepStage.LIGHT
                stageHistory += signal.timestampMillis to stage
                db.stageEventDao().insert(
                    StageEventEntity(
                        sessionId = sessionId,
                        timestampMillis = signal.timestampMillis,
                        stage = stage.name,
                        source = "bulk-proxy",
                    )
                )
            }
        }
    }

    private fun enterActivePhase() {
        if (fired || activeJob != null) return
        bulkJob?.cancel()
        acquireWakeLock()

        val repository = repo ?: run {
            Log.e(TAG, "enterActive without a session; ignoring")
            return
        }
        activeJob = scope.launch {
            repository.activeEpochs(sessionId, System.currentTimeMillis()).collect { epoch ->
                if (fired) return@collect
                handleEpoch(epoch)
            }
        }
    }

    private suspend fun handleEpoch(epoch: SensorEpoch) {
        val db = WatchServices.db
        db.sensorSampleDao().insertAll(
            listOf(
                SensorSampleEntity(
                    sessionId = sessionId, timestampMillis = epoch.startMillis,
                    type = "EPOCH_ACCEL", sampleValues = epoch.accelMagnitude, phase = SensingPhase.ACTIVE.name,
                ),
                SensorSampleEntity(
                    sessionId = sessionId, timestampMillis = epoch.startMillis,
                    type = "EPOCH_PPG", sampleValues = epoch.ppg, phase = SensingPhase.ACTIVE.name,
                ),
                SensorSampleEntity(
                    sessionId = sessionId, timestampMillis = epoch.startMillis,
                    type = "HR_IBI",
                    sampleValues = floatArrayOf(epoch.heartRateBpm) + epoch.ibiMillis.toFloatArray(),
                    phase = SensingPhase.ACTIVE.name,
                ),
            )
        )

        val features = FeatureExtraction.epochFeatures(epoch)
        val stage = estimator.update(features)
        stageHistory += epoch.startMillis to stage
        lastEpoch = epoch
        lastFeatures = features
        lastStage = stage
        db.stageEventDao().insert(
            StageEventEntity(
                sessionId = sessionId, timestampMillis = epoch.startMillis,
                stage = stage.name, source = "heuristic",
            )
        )

        // Stream to the phone; its (learned) verdict arrives async via the listener.
        val macro = MacroFeatureBuilder.fromStagePoints(stageHistory, epoch.startMillis).toFloatArray()
        scope.launch {
            com.smartwake.watch.link.DataLayerLink.broadcast(
                this@SleepSessionService,
                WirePaths.EPOCH,
                EpochCodec.encodeEpoch(
                    ActiveEpochPacket(
                        sessionId = sessionId,
                        windowStartMillis = windowStartMillis,
                        windowEndMillis = windowEndMillis,
                        mode = mode,
                        estimatedStage = stage,
                        macro = macro,
                        epoch = epoch,
                    )
                ),
            )
        }

        when (mode) {
            UsageMode.PASSIVE -> Unit // fixed-time alarm; fallback fires at target

            UsageMode.SMART_WINDOW -> {
                val decision = phoneOrLocalDecision(epoch, stage, features)
                if (decision.fire) fire("smart: ${decision.reason}")
            }

            UsageMode.RESEARCH -> {
                val warmedUp = epoch.startMillis - windowStartMillis > 2 * MINUTE_MS
                if (warmedUp && stage == researchTargetStage) {
                    fire("research: stratum $researchTargetStage")
                }
            }
        }
    }

    private fun phoneOrLocalDecision(
        epoch: SensorEpoch,
        stage: SleepStage,
        features: EpochFeatures,
    ): WakeDecision {
        val remote = phoneDecision
        val fresh = remote != null &&
            System.currentTimeMillis() - phoneDecisionReceivedAt < PHONE_DECISION_TTL_MS
        if (remote != null && fresh) {
            return WakeDecision(remote.fire, remote.score, "phone model")
        }
        return strategy.evaluate(
            WakeContext(
                nowMillis = epoch.startMillis,
                windowStartMillis = windowStartMillis,
                windowEndMillis = windowEndMillis,
                mode = mode,
                estimatedStage = stage,
                epochFeatures = features,
            )
        )
    }

    // ---- firing and teardown --------------------------------------------

    private fun fire(reason: String) {
        if (fired) return
        fired = true
        Log.i(TAG, "Firing alarm: $reason")
        AlarmScheduler.cancelAll(this)
        activeJob?.cancel()
        bulkJob?.cancel()

        val fireTime = System.currentTimeMillis()
        val macro = MacroFeatureBuilder.fromStagePoints(stageHistory, fireTime)
        val featureVector = macro.toFloatArray() +
            (lastFeatures?.toFloatArray() ?: FloatArray(4))

        scope.launch {
            val db = WatchServices.db
            val wakeEventId = db.wakeEventDao().insert(
                WakeEventEntity(
                    sessionId = sessionId,
                    fireTimestampMillis = fireTime,
                    mode = mode.name,
                    featureVector = featureVector,
                )
            )
            val exampleId = db.trainingExampleDao().insert(
                TrainingExampleEntity(
                    fireTimestampMillis = fireTime,
                    mode = mode.name,
                    macro = macro.toFloatArray(),
                    accel = lastEpoch?.accelMagnitude ?: FloatArray(0),
                    ppg = lastEpoch?.ppg ?: FloatArray(0),
                )
            )

            startVibration()
            val alarmIntent = Intent(this@SleepSessionService, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_WAKE_EVENT_ID, wakeEventId)
                .putExtra(EXTRA_EXAMPLE_ID, exampleId)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_FIRE_TIME, fireTime)
            val fullScreenPi = PendingIntent.getActivity(
                this@SleepSessionService, 10, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(this@SleepSessionService, SmartWakeApp.CHANNEL_ALARM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("SmartWake")
                .setContentText("Time to wake up")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPi, true)
                .build()
            NotificationManagerCompat.from(this@SleepSessionService).notify(NOTIF_ALARM, notif)
            try {
                startActivity(alarmIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct alarm activity launch blocked; full-screen intent will handle it")
            }
        }
    }

    private fun silenceAlarm() {
        stopVibration()
        NotificationManagerCompat.from(this).cancel(NOTIF_ALARM)
    }

    private fun completeSession() {
        silenceAlarm()
        AlarmScheduler.cancelAll(this)
        scope.launch {
            if (sessionId >= 0) {
                val dao = WatchServices.db.sleepSessionDao()
                dao.byId(sessionId)?.let { dao.update(it.copy(endMillis = System.currentTimeMillis())) }
            }
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---- plumbing --------------------------------------------------------

    private fun startForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, NOTIF_TRACKING, notification, type)
    }

    private fun trackingNotification(text: String): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SmartWakeApp.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("SmartWake")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartwake:active").also {
            it.acquire(2 * 60 * 60 * 1000L) // bounded: active window + margin
        }
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

    private fun startVibration() {
        val pattern = longArrayOf(0, 600, 400)
        vibrator().vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun stopVibration() = vibrator().cancel()

    private fun fmt(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
