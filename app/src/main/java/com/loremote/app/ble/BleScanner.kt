package com.loremote.app.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BleScanner(private val context: Context) {

    private val TAG = "BleScanner"
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner = adapter.bluetoothLeScanner

    private val _results = MutableStateFlow<List<ScanResult>>(emptyList())
    val results: StateFlow<List<ScanResult>> = _results

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val list = _results.value.toMutableList()
            val idx = list.indexOfFirst { it.device.address == result.device.address }
            if (idx >= 0) list[idx] = result else list.add(result)
            _results.value = list.sortedByDescending { it.rssi }
            Log.d(TAG, "Found: ${result.device.name ?: "Unknown"} ${result.device.address} RSSI=${result.rssi}")
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    fun start() {
        _results.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, callback)
        Log.i(TAG, "Scan started")
    }

    fun stop() {
        scanner?.stopScan(callback)
        Log.i(TAG, "Scan stopped")
    }
}
