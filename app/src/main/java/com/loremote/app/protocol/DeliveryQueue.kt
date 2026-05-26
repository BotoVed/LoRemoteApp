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
    private val prefs = context.getSharedPreferences("loremote", android.content.Context.MODE_PRIVATE)

    private data class Entry(
        val devId: String,
        val packet: OutPacket,
        var attempts: Int = 0,
        var job: Job? = null
    )

    private val queue = mutableMapOf<String, Entry>()

    private val retryCount: Int
        get() = prefs.getInt("retry_count", 0)

    private val retryInterval: Long
        get() = prefs.getInt("retry_interval", 30) * 1000L

    fun enqueue(devId: String, packet: OutPacket) {
        val key = "${devId}_${System.currentTimeMillis()}"
        queue[key] = Entry(devId, packet)
        attempt(key)
    }

    private fun attempt(key: String) {
        val entry = queue[key] ?: return
        entry.attempts++
        val hopLimit = if (entry.attempts <= 3) 0 else 7
        val pkt = entry.packet.copy(hl = hopLimit)

        entry.job = scope.launch {
            Log.d(TAG, "Attempt ${entry.attempts} for ${entry.devId} (retry=$retryCount, interval=${retryInterval}ms) hl=$hopLimit")
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
        val key = queue.keys.firstOrNull { queue[it]?.devId == devId } ?: return
        queue[key]?.job?.cancel()
        queue.remove(key)
        DeviceStateManager.onDelivered(devId)
        Log.d(TAG, "Confirmed: $devId")
    }

    fun clear() {
        queue.values.forEach { it.job?.cancel() }
        queue.clear()
    }
}
