package com.loremote.app.protocol

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DeliveryQueue(private val maxRetries: Int = 6, private val retryDelayMs: Long = 500) {
    private val TAG = "DeliveryQueue"
    private val pendingEntries = mutableMapOf<Int, Entry>()

    data class Entry(
        val packet: OutPacket,
        val deferred: CompletableDeferred<InPacket?>
    )

    suspend fun enqueue(packet: OutPacket, sender: suspend (ByteArray) -> Unit) {
        val entry = Entry(packet, CompletableDeferred())
        pendingEntries[packet.id] = entry

        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            try {
                val payload = Protocol.encodeOutPacket(packet)
                sender(payload)
                Log.v(TAG, "Sent packet ${packet.id} (attempt $attempt)")
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Send failed: ${e.message}, retry $attempt/$maxRetries")
                if (attempt < maxRetries) Thread.sleep(retryDelayMs)
            }
        }

        if (lastError != null) {
            Log.e(TAG, "Max retries reached for packet ${packet.id}")
            pendingEntries.remove(packet.id)
            entry.deferred.complete(null)
            return
        }

        if (packet.type == PacketType.PING) {
            val response = entry.deferred.await()
            Log.d(TAG, "Ping response: ${response != null}")
            pendingEntries.remove(packet.id)
        }
    }

    fun onPacketReceived(data: ByteArray) {
        try {
            val packet = Protocol.decodeInPacket(data)
            val entry = pendingEntries[packet.id]
            if (entry != null) {
                Log.d(TAG, "Response for packet ${packet.id}")
                entry.deferred.complete(packet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode packet: ${e.message}")
        }
    }
}
