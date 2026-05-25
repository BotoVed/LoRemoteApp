package com.loremote.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleManagerCallbacks
import no.nordicsemi.android.ble.callback.DataReceivedCallback
import no.nordicsemi.android.ble.callback.MtuCallback
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
val MESHTASTIC_TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
val MESHTASTIC_FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
val MESHTASTIC_FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

sealed class BleState {
    object Disconnected : BleState()
    object Connecting : BleState()
    object Connected : BleState()
    data class Error(val message: String) : BleState()
}

class LoRemoteBleManager(
    private val context: Context,
    private val onPacketReceived: (ByteArray) -> Unit
) : BleManager(context), BleManagerCallbacks {

    private val TAG = "BleManager"

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null
    private var _nusService: BluetoothGattService? = null
    private var cachedGatt: BluetoothGatt? = null

    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state

    private var pendingDevice: BluetoothDevice? = null
    private var bondingReceiver: BroadcastReceiver? = null

    init {
        registerBondingReceiver()
        setGattCallbacks(this)
    }

    private fun registerBondingReceiver() {
        bondingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    Log.d(TAG, "Bond state: ${device?.name} prev=$prevState new=$newState")
                    when (newState) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "BONDED! Connecting to ${device?.name}...")
                            pendingDevice = device
                            if (pendingDevice != null) {
                                connectTo(pendingDevice!!)
                                pendingDevice = null
                            }
                        }
                        BluetoothDevice.BOND_NONE -> {
                            Log.w(TAG, "Bond removed: ${device?.name}")
                        }
                    }
                }
            }
        }
        context.registerReceiver(
            bondingReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        Log.v(TAG, "isRequiredServiceSupported: services count=${gatt.services.size}")
        val service = gatt.getService(MESHTASTIC_SERVICE_UUID)
        if (service != null) {
            toRadioChar = service.getCharacteristic(MESHTASTIC_TO_RADIO_UUID)
            fromRadioChar = service.getCharacteristic(MESHTASTIC_FROM_RADIO_UUID)
            fromNumChar = service.getCharacteristic(MESHTASTIC_FROM_NUM_UUID)
            _nusService = service
            Log.i(TAG, "Meshtastic found: toRadio=${toRadioChar != null}, fromRadio=${fromRadioChar != null}, fromNum=${fromNumChar != null}")
            for (c in service.characteristics) {
                Log.i(TAG, "  Char: ${c.uuid} props=${c.properties}")
                for (d in c.descriptors) {
                    Log.i(TAG, "  Descriptor: ${d.uuid}")
                }
            }
        } else {
            Log.w(TAG, "Meshtastic service NOT found!")
            for (s in gatt.services) {
                Log.w(TAG, "  Available: ${s.uuid}")
            }
        }
        return toRadioChar != null && fromRadioChar != null && fromNumChar != null
    }

    override fun initialize() {
        Log.i(TAG, "Initializing device...")
        requestMtu(512)
            .with(object : MtuCallback {
                override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                    Log.i(TAG, "MTU set to $mtu")
                }
            })
            .enqueue()
        setNotificationCallback(fromNumChar).with(object : DataReceivedCallback {
            override fun onDataReceived(device: BluetoothDevice, data: Data) {
                Log.v(TAG, "FromNum notify: ${(data.value ?: ByteArray(0)).size} bytes")
                readCharacteristic(fromRadioChar!!)
                    .with(object : DataReceivedCallback {
                        override fun onDataReceived(device: BluetoothDevice, data: Data) {
                            val bytes = data.value ?: return
                            Log.v(TAG, "FromRadio read: ${bytes.size} bytes")
                            onPacketReceived(bytes)
                        }
                    })
            }
        })
        Log.i(TAG, "Notifications set up: fromNum=${fromNumChar != null}")
    }

    override fun onServicesInvalidated() {
        toRadioChar = null
        fromRadioChar = null
        fromNumChar = null
        _nusService = null
    }

    override fun onDeviceConnecting(device: BluetoothDevice) {
        _state.value = BleState.Connecting
        Log.i(TAG, "Connecting to ${device.name} (addr=${device.address})")
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        _state.value = BleState.Connected
        Log.i(TAG, "Connected to ${device.name}")
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        _state.value = BleState.Disconnected
        toRadioChar = null
        fromRadioChar = null
        fromNumChar = null
        _nusService = null
        Log.i(TAG, "Disconnected from ${device.name}")
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
    }

    override fun onLinkLossOccurred(device: BluetoothDevice) {
        _state.value = BleState.Disconnected
        Log.w(TAG, "Link loss: ${device.name}")
    }

    override fun onServicesDiscovered(device: BluetoothDevice, successful: Boolean) {
        if (successful) {
            Log.i(TAG, "Services discovered: ${device.name}")
        } else {
            Log.e(TAG, "Services discovery failed: ${device.name}")
        }
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        Log.i(TAG, "Device ready: ${device.name}")
    }

    override fun shouldEnableBatteryLevelNotifications(device: BluetoothDevice): Boolean {
        return false
    }

    override fun onBatteryValueReceived(device: BluetoothDevice, batteryValue: Int) {
    }

    override fun onBondingRequired(device: BluetoothDevice) {
        Log.w(TAG, "Pairing required: ${device.name}")
        _state.value = BleState.Error("Pairing required")
    }

    override fun onBonded(device: BluetoothDevice) {
        Log.i(TAG, "Bonded: ${device.name}")
    }

    override fun onBondingFailed(device: BluetoothDevice) {
        Log.e(TAG, "Bonding failed: ${device.name}")
        _state.value = BleState.Error("Pairing failed")
    }

    override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
        Log.e(TAG, "Error: ${device.name} $message ($errorCode)")
        _state.value = BleState.Error("$message ($errorCode)")
    }

    override fun onDeviceNotSupported(device: BluetoothDevice) {
        Log.e(TAG, "Device not supported: ${device.name}")
        _state.value = BleState.Error("Device not supported")
    }

    override fun getMinLogPriority() = Log.VERBOSE

    suspend fun send(data: ByteArray) {
        val char = toRadioChar ?: return
        Log.v(TAG, "Sending ${data.size} bytes to ToRadio")
        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .suspend()
    }

    fun connectTo(device: BluetoothDevice) {
        Log.i(TAG, "connectTo: ${device.name} (${device.address}), bondState=${device.bondState}")
        _state.value = BleState.Connecting
        connect(device)
            .retry(3, 500)
            .enqueue()
    }

    fun unRegisterReceiver() {
        bondingReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister receiver: ${e.message}")
            }
        }
        bondingReceiver = null
    }
}
