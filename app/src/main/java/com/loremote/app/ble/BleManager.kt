package com.loremote.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleManagerCallbacks
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

// ── Meshtastic BLE API UUIDs ──────────────────────────────────────────────
val MESH_SERVICE   = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
val MESH_TO_RADIO  = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
val MESH_FROM_RADIO= UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
val MESH_FROM_NUM  = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

// Meshtastic PRIVATE_APP port (наш протокол)
const val LOREMOTE_PORT = 256

sealed class BleState {
    object Disconnected : BleState()
    object Connecting   : BleState()
    object Handshake    : BleState()   // идёт Meshtastic handshake
    object Ready        : BleState()   // готов к работе
    data class Error(val message: String) : BleState()
}

class LoRemoteBleManager(
    context: Context,
    private val onPacketReceived: (ByteArray) -> Unit
) : BleManager(context), BleManagerCallbacks {

    private val TAG = "BleManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var toRadioChar:  BluetoothGattCharacteristic? = null
    private var fromRadioChar:BluetoothGattCharacteristic? = null
    private var fromNumChar:  BluetoothGattCharacteristic? = null

    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state

    // ── GattCallback ─────────────────────────────────────────────────────
    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            Log.i(TAG, "=== Services discovered ===")
            gatt.services.forEach { svc ->
                Log.i(TAG, "Service: ${svc.uuid}")
                svc.characteristics.forEach { ch ->
                    Log.i(TAG, "  Char: ${ch.uuid} props=${ch.properties}")
                }
            }
            val svc = gatt.getService(MESH_SERVICE) ?: run {
                Log.e(TAG, "Meshtastic service NOT FOUND")
                return false
            }
            toRadioChar   = svc.getCharacteristic(MESH_TO_RADIO)
            fromRadioChar = svc.getCharacteristic(MESH_FROM_RADIO)
            fromNumChar   = svc.getCharacteristic(MESH_FROM_NUM)
            val ok = toRadioChar != null && fromRadioChar != null && fromNumChar != null
            Log.i(TAG, "Required chars found: $ok")
            return ok
        }

        override fun initialize() {
            Log.i(TAG, "Initializing — requesting MTU 512")

            // 1. Request MTU
            requestMtu(512).enqueue()

            // 2. Subscribe to FromNum notifications
            setNotificationCallback(fromNumChar).with { _, _ ->
                Log.d(TAG, "FromNum notify — reading FromRadio")
                scope.launch { readFromRadioLoop() }
            }
            enableNotifications(fromNumChar).enqueue()

            // 3. Send startConfig после небольшой задержки
            scope.launch {
                delay(500)
                sendStartConfig()
            }
        }

        override fun onServicesInvalidated() {
            toRadioChar   = null
            fromRadioChar = null
            fromNumChar   = null
        }
    }

    // ── Meshtastic Handshake ──────────────────────────────────────────────

    private suspend fun sendStartConfig() {
        Log.i(TAG, "Sending startConfig...")
        _state.value = BleState.Handshake

        // ToRadio protobuf: want_config_id = field 3, varint 0
        // Manual encoding: tag = (3 << 3) | 0 = 0x18, value = 0x00
        val startConfigBytes = byteArrayOf(0x18.toByte(), 0x00)

        try {
            writeCharacteristic(
                toRadioChar,
                startConfigBytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).suspend()
            Log.i(TAG, "startConfig sent, reading FromRadio...")
            readFromRadioLoop()
        } catch (e: Exception) {
            Log.e(TAG, "startConfig failed: ${e.message}")
            _state.value = BleState.Error("Handshake failed: ${e.message}")
        }
    }

    // Читаем FromRadio пока не вернёт пустой буфер
    private suspend fun readFromRadioLoop() {
        var emptyCount = 0
        while (emptyCount < 2) {
            try {
                val data = readCharacteristic(fromRadioChar).suspend()
                val bytes = data.value
                if (bytes == null || bytes.isEmpty()) {
                    emptyCount++
                    Log.d(TAG, "FromRadio empty ($emptyCount/2)")
                } else {
                    emptyCount = 0
                    Log.d(TAG, "FromRadio data: ${bytes.size} bytes")
                    handleFromRadio(bytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ReadFromRadio error: ${e.message}")
                break
            }
        }
        // После пустого буфера — handshake завершён
        if (_state.value == BleState.Handshake) {
            Log.i(TAG, "Handshake complete — Ready!")
            _state.value = BleState.Ready
        }
    }

    // Разбор FromRadio — нас интересуют только PRIVATE_APP пакеты
    private fun handleFromRadio(bytes: ByteArray) {
        // FromRadio это protobuf. Нас интересует поле packet (field 2).
        // Внутри MeshPacket.decoded.portnum == 256 (PRIVATE_APP) — наши данные.
        // Простой вариант: пробуем декодировать как наш MessagePack напрямую.
        // Если Meshtastic завернул пакет в protobuf — нужно распаковать.
        // Для начала — передаём байты наверх, MainActivity разберётся.
        Log.d(TAG, "FromRadio packet: ${bytes.toHex()}")
        onPacketReceived(bytes)
    }

    // ── Send ──────────────────────────────────────────────────────────────

    suspend fun send(data: ByteArray) {
        val char = toRadioChar ?: run {
            Log.e(TAG, "toRadioChar is null")
            return
        }
        // Фрагментация по MTU
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + chunkSize, data.size))
            writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
            offset += chunkSize
        }
        Log.v(TAG, "Sent ${data.size}b in ${(data.size + chunkSize - 1) / chunkSize} chunks, mtu=$mtu")
    }

    // ── Connect ───────────────────────────────────────────────────────────

    fun connectTo(device: BluetoothDevice) {
        setGattCallbacks(this)
        _state.value = BleState.Connecting
        Log.i(TAG, "Connecting to ${device.name} (${device.address})")
        connect(device)
            .retry(3, 1000)
            .useAutoConnect(false)
            .enqueue()
    }

    // ── BleManagerCallbacks ───────────────────────────────────────────────

    override fun onDeviceConnecting(device: BluetoothDevice) {
        _state.value = BleState.Connecting
        Log.i(TAG, "Connecting: ${device.name}")
    }
    override fun onDeviceConnected(device: BluetoothDevice) {
        Log.i(TAG, "Connected: ${device.name}")
        // state будет обновлён после handshake
    }
    override fun onDeviceReady(device: BluetoothDevice) {
        Log.i(TAG, "Device ready: ${device.name}")
    }
    override fun onDeviceDisconnecting(device: BluetoothDevice) {}
    override fun onDeviceDisconnected(device: BluetoothDevice) {
        _state.value = BleState.Disconnected
        toRadioChar = null; fromRadioChar = null; fromNumChar = null
        Log.i(TAG, "Disconnected: ${device.name}")
    }
    override fun onLinkLossOccurred(device: BluetoothDevice) {
        _state.value = BleState.Disconnected
        Log.w(TAG, "Link loss: ${device.name}")
    }
    override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {}
    override fun onDeviceNotSupported(device: BluetoothDevice) {
        _state.value = BleState.Error("Device not supported — Meshtastic service not found")
        Log.e(TAG, "Device not supported: ${device.name}")
    }
    override fun onBondingRequired(device: BluetoothDevice) {
        Log.w(TAG, "Bonding required: ${device.name}")
    }
    override fun onBonded(device: BluetoothDevice) {
        Log.i(TAG, "Bonded: ${device.name}")
    }
    override fun onBondingFailed(device: BluetoothDevice) {
        _state.value = BleState.Error("Bonding failed")
        Log.e(TAG, "Bonding failed: ${device.name}")
    }
    override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
        _state.value = BleState.Error("$message ($errorCode)")
        Log.e(TAG, "Error: ${device.name} $message ($errorCode)")
    }
    override fun shouldEnableBatteryLevelNotifications(device: BluetoothDevice) = false
    override fun onBatteryValueReceived(device: BluetoothDevice, value: Int) {}

    // ── Util ──────────────────────────────────────────────────────────────
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
