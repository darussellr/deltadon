package com.smartwake.watch.data

import android.content.Context
import com.smartwake.shared.model.MINUTE_MS
import com.smartwake.shared.model.UsageMode
import java.util.Calendar

/** User config: usage mode, wake target time, and window length. */
class SleepConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("smartwake_config", Context.MODE_PRIVATE)

    var mode: UsageMode
        get() = UsageMode.valueOf(prefs.getString("mode", UsageMode.PASSIVE.name)!!)
        set(value) = prefs.edit().putString("mode", value.name).apply()

    var targetHour: Int
        get() = prefs.getInt("targetHour", 7)
        set(value) = prefs.edit().putInt("targetHour", value).apply()

    var targetMinute: Int
        get() = prefs.getInt("targetMinute", 0)
        set(value) = prefs.edit().putInt("targetMinute", value).apply()

    var windowMinutes: Int
        get() = prefs.getInt("windowMinutes", 30)
        set(value) = prefs.edit().putInt("windowMinutes", value).apply()

    data class Schedule(
        val windowStartMillis: Long,
        val windowEndMillis: Long,
        val mode: UsageMode,
    )

    /**
     * Next occurrence of the target time. The window ends at the target; the
     * active phase always starts windowMinutes earlier (even in PASSIVE mode,
     * where the alarm fires exactly at the target — the active phase then just
     * collects the dataset without moving the fire time).
     */
    fun computeSchedule(nowMillis: Long): Schedule {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
        val target = cal.timeInMillis
        return Schedule(
            windowStartMillis = target - windowMinutes * MINUTE_MS,
            windowEndMillis = target,
            mode = mode,
        )
    }
}
