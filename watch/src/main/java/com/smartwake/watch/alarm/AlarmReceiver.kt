package com.smartwake.watch.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.smartwake.watch.service.SleepSessionService

/** Forwards exact-alarm firings into the (already running) session service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val forward = Intent(context, SleepSessionService::class.java).setAction(intent.action)
        ContextCompat.startForegroundService(context, forward)
    }
}
