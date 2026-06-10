package com.smartwake.phone.link

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.smartwake.phone.data.PhoneServices
import com.smartwake.phone.ml.PhoneInferenceEngine
import com.smartwake.shared.db.TrainingExampleEntity
import com.smartwake.shared.link.DecisionPacket
import com.smartwake.shared.link.EpochCodec
import com.smartwake.shared.link.WirePaths
import com.smartwake.shared.model.UsageMode
import com.smartwake.shared.wake.DecisionPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * Phone end of the Data Layer link: scores incoming epochs with the TFLite
 * model and returns wake decisions; stores labeled training examples shipped
 * over after the user logs morning energy.
 *
 * If no model is installed yet, epochs go unanswered on purpose — silence
 * means the watch's local heuristic stays in charge.
 */
class PhoneListenerService : WearableListenerService() {

    private companion object {
        const val TAG = "PhoneListener"
        const val SMART_WINDOW_EPSILON = 0.1f
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WirePaths.EPOCH -> handleEpoch(event)
            WirePaths.LABEL -> handleLabel(event)
            else -> super.onMessageReceived(event)
        }
    }

    private fun handleEpoch(event: MessageEvent) {
        val packet = EpochCodec.decodeEpoch(event.data)
        val engine = PhoneInferenceEngine.get(this)
        val score = engine.scoreEpoch(packet.macro, packet.epoch.accelMagnitude, packet.epoch.ppg)
            ?: return // no model yet -> watch heuristic decides

        val decision = DecisionPolicy.decide(
            score = score,
            estimatedStage = packet.estimatedStage,
            nowMillis = packet.epoch.startMillis,
            windowStartMillis = packet.windowStartMillis,
            windowEndMillis = packet.windowEndMillis,
            exploreEpsilon = if (packet.mode == UsageMode.SMART_WINDOW) SMART_WINDOW_EPSILON else 0f,
        )
        val reply = EpochCodec.encodeDecision(
            DecisionPacket(packet.epoch.startMillis, decision.fire, decision.score)
        )
        runBlocking {
            try {
                Wearable.getMessageClient(this@PhoneListenerService)
                    .sendMessage(event.sourceNodeId, WirePaths.DECISION, reply)
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Decision reply failed: ${e.message}")
            }
        }
    }

    private fun handleLabel(event: MessageEvent) {
        val label = EpochCodec.decodeLabel(event.data)
        runBlocking {
            PhoneServices.db(this@PhoneListenerService).trainingExampleDao().insert(
                TrainingExampleEntity(
                    fireTimestampMillis = label.fireTimestampMillis,
                    mode = label.mode.name,
                    macro = label.macro,
                    accel = label.accel,
                    ppg = label.ppg,
                    score = label.score,
                    tags = label.tags.ifEmpty { null },
                    responseLatencyMillis = label.responseLatencyMillis,
                )
            )
        }
        Log.i(TAG, "Stored labeled example (score=${label.score})")
    }
}
