package com.smartwake.watch.link

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper over the Wearable MessageClient. Failures are logged and
 * swallowed — the watch always has its local heuristic as fallback, so a
 * missing/unreachable phone must never break the alarm.
 */
object DataLayerLink {

    private const val TAG = "DataLayerLink"

    suspend fun broadcast(context: Context, path: String, data: ByteArray) {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val messageClient = Wearable.getMessageClient(context)
            for (node in nodes) {
                messageClient.sendMessage(node.id, path, data).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Data Layer send to $path failed: ${e.message}")
        }
    }
}
