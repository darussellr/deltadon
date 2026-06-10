package com.smartwake.watch.data

import android.content.Context
import android.util.Log
import com.smartwake.shared.db.SmartWakeDatabase
import com.smartwake.shared.model.EPOCH_MILLIS
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.sensing.FakeSleepSensingRepository
import com.smartwake.shared.sensing.SleepSensingRepository
import com.smartwake.shared.sensing.SyntheticNightGenerator

/** Simple service locator for the watch app. */
object WatchServices {

    private const val TAG = "WatchServices"
    private const val SAMSUNG_REPO_CLASS = "com.smartwake.watch.sensing.SamsungSensingRepository"

    lateinit var appContext: Context
        private set
    lateinit var db: SmartWakeDatabase
        private set
    lateinit var config: SleepConfigStore
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        db = SmartWakeDatabase.build(appContext)
        config = SleepConfigStore(appContext)
    }

    val isSamsungSdkPresent: Boolean
        get() = try {
            Class.forName(SAMSUNG_REPO_CLASS)
            true
        } catch (e: ClassNotFoundException) {
            false
        }

    /**
     * Samsung Health Sensor SDK implementation when the AAR is bundled
     * (see watch/libs/README.md), otherwise a synthetic night anchored at
     * [bedTimeMillis] that emits at real-time pace — so the full alarm flow
     * is exercisable on an emulator with no hardware.
     */
    fun sensingRepository(bedTimeMillis: Long): SleepSensingRepository {
        if (isSamsungSdkPresent) {
            try {
                return Class.forName(SAMSUNG_REPO_CLASS)
                    .getConstructor(Context::class.java)
                    .newInstance(appContext) as SleepSensingRepository
            } catch (e: Exception) {
                Log.e(TAG, "Samsung repo present but failed to init, falling back to fake", e)
            }
        }
        return FakeSleepSensingRepository(
            night = SyntheticNightGenerator(
                SyntheticNightGenerator.NightSpec(bedTimeMillis = bedTimeMillis, seed = bedTimeMillis)
            ),
            delayPerBulkSignalMillis = MINUTE_MS,
            delayPerEpochMillis = EPOCH_MILLIS,
        )
    }
}
