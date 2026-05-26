package com.loremote.app.protocol

import android.util.Log
import com.loremote.app.state.DeviceStateManager
import kotlinx.coroutines.*

class DeliveryQueue(
    private val sendFn: suspend (OutPacket) -> Unit,
    private val onFailed: (String) -> Unit,
    private val context: android.content.Context
) {
    private val TAG = "DeliveryQueue"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class Entry(
        val devId: String,
        val packet: OutPacket,
        var attempts: Int = 0,
        var job: Job? = null,
        var lastAttempt: Long = 0
    )

    private val queue = LinkedHashMap<String, Entry>()

    fun enqueue(devId: String, packet: OutPacket) {
        // Удалить старую запись для этого устройства
        queue.remove(devId)
        queue[devId] = Entry(devId, packet)
        attempt(devId)
    }

    private fun attempt(key: String) {
        val prefs = context.getSharedPreferences("loremote", android.content.Context.MODE_PRIVATE)
        val retryCount = prefs.getInt("retry_count", 0)
        val retryInterval = prefs.getLong("retry_interval", 30) * 1000L

        val entry = queue[key] ?: return
        entry.attempts++
        val hopLimit = if (entry.attempts <= 3) 0 else 7
        val pkt = entry.packet.copy(hl = hopLimit)

        entry.job = scope.launch {
            Log.d(TAG, "Attempt ${entry.attempts} for ${entry.devId} (retry=$retryCount, interval=${retryInterval}ms) hl=$hopLimit")

            entry.lastAttempt = System.currentTimeMillis()
            try { sendFn(pkt) } catch (e: Exception) { Log.e(TAG, "Send error: ${e.message}") }

            if (entry.attempts > retryCount) {
                queue.remove(key)
                DeviceStateManager.onFailed(entry.devId)
                withContext(Dispatchers.Main) { onFailed(entry.devId) }
                return@launch
            }
            delay(retryInterval)
            attempt(key)
        }
    }

    fun confirm(devId: String) {
        val entry = queue.remove(devId)
        entry?.job?.cancel()
        DeviceStateManager.onDelivered(devId)
        Log.d(TAG, "Confirmed: $devId")
    }

    fun clear() {
        queue.values.forEach { it.job?.cancel() }
        queue.clear()
    }

    fun getQueueEntries(): List<QueueEntry> {
        return queue.values.map { entry ->
            QueueEntry(
                devId = entry.devId,
                attempts = entry.attempts,
                lastAttempt = if (entry.lastAttempt > 0) entry.lastAttempt else entry.packet._ts ?: 0
            )
        }
    }

    data class QueueEntry(
        val devId: String,
        val attempts: Int,
        val lastAttempt: Long
    )
}
