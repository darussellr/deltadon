package com.smartwake.watch

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.smartwake.shared.model.UsageMode
import com.smartwake.watch.alarm.AlarmScheduler
import com.smartwake.watch.data.WatchServices
import com.smartwake.watch.service.SleepSessionService

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ConfigScreen(
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BODY_SENSORS,
                                Manifest.permission.ACTIVITY_RECOGNITION,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                    },
                    onRequestExactAlarm = {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    },
                    onStartSession = {
                        startForegroundService(
                            Intent(this, SleepSessionService::class.java)
                                .setAction(SleepSessionService.ACTION_START_SESSION)
                        )
                    },
                    onStopSession = {
                        startService(
                            Intent(this, SleepSessionService::class.java)
                                .setAction(SleepSessionService.ACTION_COMPLETE)
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    onRequestPermissions: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    val config = WatchServices.config
    var mode by remember { mutableStateOf(config.mode) }
    var hour by remember { mutableStateOf(config.targetHour) }
    var minute by remember { mutableStateOf(config.targetMinute) }
    var window by remember { mutableStateOf(config.windowMinutes) }
    var running by remember { mutableStateOf(SleepSessionService.isRunning) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val exactOk = remember { AlarmScheduler.canScheduleExact(context) }

    LaunchedEffect(Unit) { onRequestPermissions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("SmartWake", style = MaterialTheme.typography.title2)
        Text(
            if (WatchServices.isSamsungSdkPresent) "sensors: Samsung SDK" else "sensors: synthetic",
            style = MaterialTheme.typography.caption2,
        )

        Chip(
            onClick = {
                mode = UsageMode.entries[(mode.ordinal + 1) % UsageMode.entries.size]
                config.mode = mode
            },
            label = { Text("Mode: ${mode.name.lowercase().replace('_', ' ')}") },
            modifier = Modifier.fillMaxWidth(),
        )

        Stepper(
            label = "Wake by %02d:%02d".format(hour, minute),
            onMinus = {
                val total = (hour * 60 + minute - 15 + 24 * 60) % (24 * 60)
                hour = total / 60; minute = total % 60
                config.targetHour = hour; config.targetMinute = minute
            },
            onPlus = {
                val total = (hour * 60 + minute + 15) % (24 * 60)
                hour = total / 60; minute = total % 60
                config.targetHour = hour; config.targetMinute = minute
            },
        )

        Stepper(
            label = "Window: $window min",
            onMinus = {
                window = (window - 15).coerceAtLeast(15)
                config.windowMinutes = window
            },
            onPlus = {
                window = (window + 15).coerceAtMost(120)
                config.windowMinutes = window
            },
        )

        if (!exactOk) {
            Button(onClick = onRequestExactAlarm) { Text("Allow exact alarms") }
        }

        Spacer(Modifier.height(4.dp))
        if (running) {
            Button(onClick = {
                onStopSession()
                running = false
            }) { Text("Stop session") }
        } else {
            Button(onClick = {
                onStartSession()
                running = true
            }) { Text("Start sleep") }
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(onClick = onMinus) { Text("−") }
        Spacer(Modifier.width(6.dp))
        Text(label, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Button(onClick = onPlus) { Text("+") }
    }
}
