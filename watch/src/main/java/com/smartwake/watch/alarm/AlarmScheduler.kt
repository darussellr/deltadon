package com.smartwake.watch.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartwake.watch.service.SleepSessionService

/**
 * Exact alarms drive the two hard time points of a session: ramping up to the
 * active phase at window start, and the guaranteed fallback fire at window end.
 * Everything in between is decided per-epoch inside the service.
 */
object AlarmScheduler {

    private const val RC_ENTER_ACTIVE = 1
    private const val RC_FALLBACK = 2

    fun scheduleEnterActive(context: Context, atMillis: Long) =
        scheduleExact(context, atMillis, RC_ENTER_ACTIVE, SleepSessionService.ACTION_ENTER_ACTIVE)

    fun scheduleFallbackFire(context: Context, atMillis: Long) =
        scheduleExact(context, atMillis, RC_FALLBACK, SleepSessionService.ACTION_FALLBACK_FIRE)

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context, RC_ENTER_ACTIVE, SleepSessionService.ACTION_ENTER_ACTIVE))
        am.cancel(pendingIntent(context, RC_FALLBACK, SleepSessionService.ACTION_FALLBACK_FIRE))
    }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun scheduleExact(context: Context, atMillis: Long, requestCode: Int, action: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, requestCode, action)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            // Degraded but functional; MainActivity surfaces the grant flow.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
