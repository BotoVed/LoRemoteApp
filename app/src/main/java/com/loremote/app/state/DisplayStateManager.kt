package com.loremote.app.state

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class DeviceStatus { OK, PENDING, FAILED }

data class DeviceState(
    val hash: String,
    val current: Map<String, Any?>,
    val pending: Map<String, Any?>?,
    val status: DeviceStatus = DeviceStatus.OK
)

object DisplayStateManager {
    private const val TAG = "DisplayStateManager"

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

    fun get(hash: String): DeviceState? = _states.value[hash]

    fun getValues(hash: String): Map<String, Any?> {
        val s = _states.value[hash] ?: return emptyMap()
        return if (s.pending != null) s.current + s.pending else s.current
    }

    fun isEnabled(hash: String): Boolean {
        return _states.value[hash] != null
    }

    fun onConfirmed(hash: String, values: Map<String, Any?>) {
        _states.value = _states.value + (hash to DeviceState(
            hash = hash,
            current = values,
            pending = null,
            status = DeviceStatus.OK
        ))
        Log.d(TAG, "Confirmed $hash: $values")
    }

    fun onSending(hash: String, changes: Map<String, Any?>) {
        val old = _states.value[hash] ?: DeviceState(hash, emptyMap(), null)
        _states.value = _states.value + (hash to old.copy(
            pending = changes,
            status = DeviceStatus.PENDING
        ))
        Log.d(TAG, "Pending $hash: $changes")
    }

    fun onDelivered(hash: String) {
        val old = _states.value[hash] ?: return
        val merged = old.current + (old.pending ?: emptyMap())
        _states.value = _states.value + (hash to DeviceState(
            hash = hash, current = merged, pending = null, status = DeviceStatus.OK
        ))
        Log.d(TAG, "Delivered $hash")
    }

    fun onFailed(hash: String) {
        val old = _states.value[hash] ?: return
        _states.value = _states.value + (hash to old.copy(
            pending = null, status = DeviceStatus.FAILED
        ))
        Log.w(TAG, "Failed $hash, rolling back to: ${old.current}")
        GlobalScope.launch(Dispatchers.Main) {
            delay(3000)
            val cur = _states.value[hash] ?: return@launch
            if (cur.status == DeviceStatus.FAILED) {
                _states.value = _states.value + (hash to cur.copy(status = DeviceStatus.OK))
            }
        }
    }

    fun clear() { _states.value = emptyMap() }
}
