package com.smartwake.watch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.smartwake.watch.data.WatchServices

class SmartWakeApp : Application() {

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALARM = "alarm"
    }

    override fun onCreate() {
        super.onCreate()
        WatchServices.init(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Sleep tracking", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
            }
        )
    }
}
