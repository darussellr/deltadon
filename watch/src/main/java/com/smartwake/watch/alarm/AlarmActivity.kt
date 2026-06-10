package com.smartwake.watch.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.smartwake.shared.db.EnergyLabelEntity
import com.smartwake.shared.link.EpochCodec
import com.smartwake.shared.link.LabelPacket
import com.smartwake.shared.link.WirePaths
import com.smartwake.shared.model.UsageMode
import com.smartwake.watch.data.WatchServices
import com.smartwake.watch.link.DataLayerLink
import com.smartwake.watch.service.SleepSessionService
import kotlinx.coroutines.launch

/**
 * Full-screen alarm: dismiss -> energy score (1–10) -> optional tags -> save.
 * Response latency (fire -> score entry) is stored as a behavioral alertness proxy.
 */
class AlarmActivity : ComponentActivity() {

    private var wakeEventId = -1L
    private var exampleId = -1L
    private var fireTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        wakeEventId = intent.getLongExtra(SleepSessionService.EXTRA_WAKE_EVENT_ID, -1L)
        exampleId = intent.getLongExtra(SleepSessionService.EXTRA_EXAMPLE_ID, -1L)
        fireTime = intent.getLongExtra(SleepSessionService.EXTRA_FIRE_TIME, System.currentTimeMillis())

        setContent {
            MaterialTheme {
                AlarmFlow(
                    onDismiss = { sendServiceAction(SleepSessionService.ACTION_SILENCE) },
                    onSave = { score, tags -> saveLabel(score, tags) },
                )
            }
        }
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, SleepSessionService::class.java).setAction(action))
    }

    private fun saveLabel(score: Int, tags: List<String>) {
        val latency = System.currentTimeMillis() - fireTime
        val tagString = tags.joinToString(",")
        lifecycleScope.launch {
            val db = WatchServices.db
            if (wakeEventId >= 0) {
                db.energyLabelDao().insert(
                    EnergyLabelEntity(
                        wakeEventId = wakeEventId,
                        score = score,
                        tags = tagString.ifEmpty { null },
                        responseLatencyMillis = latency,
                    )
                )
            }
            if (exampleId >= 0) {
                db.trainingExampleDao().applyLabel(exampleId, score, tagString.ifEmpty { null }, latency)
                // Ship the completed training example to the phone.
                db.trainingExampleDao().all().firstOrNull { it.id == exampleId }?.let { example ->
                    DataLayerLink.broadcast(
                        this@AlarmActivity,
                        WirePaths.LABEL,
                        EpochCodec.encodeLabel(
                            LabelPacket(
                                fireTimestampMillis = example.fireTimestampMillis,
                                mode = UsageMode.valueOf(example.mode),
                                macro = example.macro,
                                accel = example.accel,
                                ppg = example.ppg,
                                score = score,
                                tags = tagString,
                                responseLatencyMillis = latency,
                            )
                        ),
                    )
                }
            }
            sendServiceAction(SleepSessionService.ACTION_COMPLETE)
            finish()
        }
    }
}

private enum class AlarmStep { RINGING, SCORE, TAGS }

@Composable
private fun AlarmFlow(
    onDismiss: () -> Unit,
    onSave: (Int, List<String>) -> Unit,
) {
    var step by remember { mutableStateOf(AlarmStep.RINGING) }
    var score by remember { mutableStateOf(0) }
    val selectedTags = remember { mutableStateOf(setOf<String>()) }

    when (step) {
        AlarmStep.RINGING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Wake up", style = MaterialTheme.typography.title1)
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    onDismiss()
                    step = AlarmStep.SCORE
                }) {
                    Text("Dismiss")
                }
            }
        }

        AlarmStep.SCORE -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("How's your energy?", textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 1..5) {
                        val value = row * 5 + col
                        Button(
                            onClick = {
                                score = value
                                step = AlarmStep.TAGS
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text("$value", style = MaterialTheme.typography.caption1)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        AlarmStep.TAGS -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tags (optional)", textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            for (tag in listOf("groggy", "alert", "headache")) {
                val selected = tag in selectedTags.value
                Chip(
                    onClick = {
                        selectedTags.value =
                            if (selected) selectedTags.value - tag else selectedTags.value + tag
                    },
                    label = { Text(if (selected) "✓ $tag" else tag) },
                    colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSave(score, selectedTags.value.toList()) }) {
                Text("Save")
            }
        }
    }
}
