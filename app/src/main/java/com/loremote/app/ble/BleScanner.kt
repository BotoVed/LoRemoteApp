package com.loremote.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleScanner(
    private val context: Context,
    private val bleManager: LoRemoteBleManager
) {
    private val TAG = "BleScanner"
    private val bluetoothAdapter: BluetoothAdapter =
        BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth not supported")

    private val _devices = MutableStateFlow<List<ScanResult>>(emptyList())
    val devices: StateFlow<List<ScanResult>> = _devices.asStateFlow()

    private var scanCallback: ScanCallback? = null

    fun scan() {
        Log.d(TAG, "Starting scan...")
        _devices.value = emptyList()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                result?.let {
                    val current = _devices.value.toMutableList()
                    val idx = current.indexOfFirst { it.device.address == result.device.address }
                    if (idx >= 0) {
                        current[idx] = it
                    } else {
                        current.add(it)
                    }
                    _devices.value = current
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothAdapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        }
        Log.d(TAG, "Scan started")
    }

    fun stopScan() {
        scanCallback?.let {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(it)
            Log.d(TAG, "Scan stopped")
        }
    }
}
