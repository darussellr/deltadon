package com.smartwake.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartwake.phone.data.PhoneServices
import com.smartwake.phone.ml.PhoneInferenceEngine
import com.smartwake.phone.ml.TrainingDataExporter
import com.smartwake.shared.db.TrainingExampleEntity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    PhoneApp()
                }
            }
        }
    }
}

@Composable
private fun PhoneApp() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("History", "Model", "Setup").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> HistoryTab()
            1 -> ModelTab()
            2 -> SetupTab()
        }
    }
}

@Composable
private fun HistoryTab() {
    val context = LocalContext.current
    var examples by remember { mutableStateOf<List<TrainingExampleEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        examples = PhoneServices.db(context).trainingExampleDao().all()
    }

    if (examples.isEmpty()) {
        Column(Modifier.padding(24.dp)) {
            Text("No wake events yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Labeled wake events stream over from the watch after each " +
                    "morning's energy score and show up here."
            )
        }
        return
    }

    val fmt = remember { SimpleDateFormat("EEE MMM d, HH:mm", Locale.US) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(examples) { example ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        fmt.format(Date(example.fireTimestampMillis)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("Mode: ${example.mode.lowercase().replace('_', ' ')}")
                    Text("Energy: ${example.score?.toString() ?: "unlabeled"}" +
                        (example.tags?.let { "  •  $it" } ?: ""))
                }
            }
        }
    }
}

@Composable
private fun ModelTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { PhoneInferenceEngine.get(context) }
    var labeledCount by remember { mutableStateOf(0) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        labeledCount = PhoneServices.db(context).trainingExampleDao().labeled().size
    }

    Column(Modifier.padding(24.dp)) {
        Text("Model status", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (engine.isModelAvailable()) {
            val file = File(engine.modelFilePath())
            Text("Model installed (${file.length() / 1024} KB)")
            Text("Updated: ${SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(file.lastModified()))}")
        } else {
            Text("No model installed — the watch heuristic is making wake decisions.")
            Spacer(Modifier.height(4.dp))
            Text(
                "Train one with training/train_base_model.py, then:\n" +
                    "adb push smartwake.tflite ${engine.modelFilePath()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Training data", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("$labeledCount labeled examples collected")
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                val file = TrainingDataExporter.export(context)
                exportResult = file?.let { "Exported to ${it.absolutePath}" } ?: "Nothing to export yet"
            }
        }) {
            Text("Export training data")
        }
        exportResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SetupTab() {
    Column(Modifier.padding(24.dp)) {
        Text("Setup", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "1. Install the watch app and grant sensor permissions.\n\n" +
                "2. Usage mode, wake target, and window length are set on the watch.\n\n" +
                "3. For real sensors: drop the Samsung Health Sensor SDK AAR into " +
                "watch/libs/ and enable Health Platform developer mode on the watch " +
                "(see watch/libs/README.md). Until then the watch runs synthetic nights.\n\n" +
                "4. This phone scores epochs with the TFLite model during the wake " +
                "window; with no model installed the watch's heuristic decides locally."
        )
    }
}
