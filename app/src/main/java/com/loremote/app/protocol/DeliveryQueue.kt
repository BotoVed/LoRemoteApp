package com.loremote.app.protocol

import android.content.Context
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

    data class Entry(
        val devId: String,
        val packet: OutPacket,
        val oldValue: Map<String, Any?>,
        val newValue: Map<String, Any?>,
        var sendTime: Long = 0L,
        var attempts: Int = 0,
        var confirmed: Boolean = false
    )

    private val queue = LinkedHashMap<String, Entry>()

    private var loopJob: Job? = null

    fun start() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                delay(1000)
                processQueue()
            }
        }
    }

    fun stop() { loopJob?.cancel() }

    private suspend fun processQueue() {
        val prefs = context.getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val maxRetries = prefs.getInt("retry_count", 0).toLong()
        val intervalMs = prefs.getInt("retry_interval", 30).toLong() * 1000L
        val now = System.currentTimeMillis()

        val keys = synchronized(queue) { queue.keys.toList() }

        for (key in keys) {
            val entry = synchronized(queue) { queue[key] } ?: continue

            when {
                entry.confirmed -> {
                    synchronized(queue) { queue.remove(key) }
                    DeviceStateManager.onConfirmed(entry.devId, entry.newValue)
                    Log.d(TAG, "Confirmed and applied: ${entry.devId}")
                }

                entry.sendTime == 0L || now >= entry.sendTime + intervalMs -> {
                    if (entry.attempts <= maxRetries) {
                        entry.attempts++
                        entry.sendTime = now
                        Log.d(TAG, "Sending attempt ${entry.attempts}/${maxRetries+1} for ${entry.devId}")
                        try {
                            sendFn(entry.packet)
                        } catch (e: Exception) {
                            Log.e(TAG, "Send error for ${entry.devId}: ${e.message}")
                        }
                    } else {
                        synchronized(queue) { queue.remove(key) }
                        DeviceStateManager.onConfirmed(entry.devId, entry.oldValue)
                        withContext(Dispatchers.Main) { onFailed(entry.devId) }
                        Log.w(TAG, "Failed after ${entry.attempts} attempts: ${entry.devId}, rolling back")
                    }
                }

                else -> Log.v(TAG, "Waiting for ${entry.devId}, next in ${(entry.sendTime + intervalMs - now)/1000}s")
            }
        }
    }

    fun enqueue(devId: String, packet: OutPacket, oldValue: Map<String, Any?>, newValue: Map<String, Any?>) {
        val effectiveOldValue = synchronized(queue) {
            queue[devId]?.oldValue ?: oldValue
        }
        synchronized(queue) {
            queue[devId] = Entry(
                devId = devId,
                packet = packet,
                oldValue = effectiveOldValue,
                newValue = newValue,
                sendTime = 0L,
                attempts = 0,
                confirmed = false
            )
        }
        Log.d(TAG, "Enqueued: $devId old=$effectiveOldValue new=$newValue")
    }

    fun confirm(devId: String, confirmedValues: Map<String, Any?>) {
        val entry = synchronized(queue) { queue[devId] }
        if (entry != null) {
            synchronized(queue) { entry.confirmed = true }
            Log.d(TAG, "Marked confirmed: $devId")
        } else {
            DeviceStateManager.onConfirmed(devId, confirmedValues)
            Log.d(TAG, "Late confirm (no entry): $devId = $confirmedValues")
        }
    }

    fun clear() {
        synchronized(queue) { queue.clear() }
    }

    fun getQueueEntries(): List<QueueEntry> = synchronized(queue) {
        queue.values.map { QueueEntry(it.devId, it.attempts, it.sendTime) }
    }

    data class QueueEntry(val devId: String, val attempts: Int, val lastAttempt: Long)
}
