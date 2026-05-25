package com.loremote.app.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.loremote.app.R
import com.loremote.app.ble.LoRemoteBleManager
import com.loremote.app.ble.BleState
import com.loremote.app.ble.BleScanner
import com.loremote.app.protocol.DeliveryQueue
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.InPacket
import com.loremote.app.protocol.Protocol
import com.loremote.app.protocol.PacketType
import com.loremote.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class DeviceItem(val name: String, val address: String, val rssi: Int)

class DeviceAdapter(
    private val context: android.content.Context,
    var items: MutableList<DeviceItem> = mutableListOf()
) : ArrayAdapter<DeviceItem>(context, 0) {
    var selectedPosition = -1

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        val item = items[position]
        val isSelected = position == selectedPosition
        textView.text = "${item.name} (${item.address}) RSSI: ${item.rssi}"
        textView.setTextColor(if (isSelected) ContextCompat.getColor(context, android.R.color.holo_blue_dark) else ContextCompat.getColor(context, android.R.color.black))
        return view
    }

    fun addAll(newItems: List<DeviceItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var bleManager: LoRemoteBleManager
    private lateinit var bleScanner: BleScanner
    private lateinit var deliveryQueue: DeliveryQueue
    private lateinit var deviceList: ListView
    private lateinit var deviceAdapter: DeviceAdapter
    private var selectedDevice: DeviceItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceList = findViewById(R.id.deviceList)

        bleManager = LoRemoteBleManager(this) { bytes ->
            Log.d(TAG, "RX: ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
        bleScanner = BleScanner(this, bleManager)
        deliveryQueue = DeliveryQueue()

        observeBleState()

        deviceList.setOnItemClickListener { _, _, position, _ ->
            val item = deviceAdapter.items[position]
            selectedDevice = item
            deviceAdapter.selectedPosition = position
            deviceAdapter.notifyDataSetChanged()
            Log.d(TAG, "Selected: ${item.name}")
        }

        findViewById<View>(R.id.btnScan).setOnClickListener { startScan() }
        findViewById<View>(R.id.btnConnect).setOnClickListener { connectToDevice() }
        findViewById<View>(R.id.btnPing).setOnClickListener { sendPing() }
        findViewById<View>(R.id.btnAll).setOnClickListener { sendAll() }
        findViewById<View>(R.id.btnDisconnect).setOnClickListener { disconnect() }
    }

    private fun observeBleState() {
        coroutineScope.launch {
            bleManager.state.collect { state ->
                when (state) {
                    BleState.Disconnected -> updateStatus("Disconnected")
                    BleState.Connecting -> updateStatus("Connecting...")
                    BleState.Connected -> updateStatus("Connected")
                    is BleState.Error -> updateStatus("Error: ${state.message}")
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        findViewById<TextView>(R.id.statusText).text = text
    }

    private fun startScan() {
        deviceAdapter = DeviceAdapter(this, mutableListOf())
        deviceList.adapter = deviceAdapter
        bleScanner.scan()
        coroutineScope.launch {
            bleScanner.devices.collect { devices ->
                val items = devices.map { DeviceItem(it.device.name ?: it.device.address, it.device.address, it.rssi) }
                deviceAdapter.addAll(items)
                Log.d(TAG, "Devices found: ${items.size}")
            }
        }
    }

    private fun connectToDevice() {
        val device = selectedDevice ?: run {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch {
            val bluetoothDevice = App.context.getSystemService(android.bluetooth.BluetoothAdapter::class.java)?.getRemoteDevice(device.address)
                ?: run { Toast.makeText(this@MainActivity, "Bluetooth not available", Toast.LENGTH_SHORT).show(); return@launch }
            Log.d(TAG, "Connecting to ${device.name}...")
            bleManager.connectTo(bluetoothDevice)
        }
    }

    private fun sendPing() {
        val packet = OutPacket(
            type = PacketType.PING,
            hop_limit = 7,
            data = ByteArray(0)
        )
        coroutineScope.launch {
            deliveryQueue.enqueue(packet) { data ->
                bleManager.send(data)
            }
            Log.d(TAG, "TX ping: ${packet.id}")
        }
    }

    private fun sendAll() {
        for (type in PacketType.entries) {
            if (type == PacketType.PING) continue
            val packet = OutPacket(
                type = type,
                hop_limit = 7,
                data = ByteArray(0)
            )
            coroutineScope.launch {
                deliveryQueue.enqueue(packet) { data ->
                    bleManager.send(data)
                }
                Log.d(TAG, "TX ${type}: ${packet.id}")
            }
        }
    }

    private fun disconnect() {
        coroutineScope.launch {
            bleManager.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.unRegisterReceiver()
        bleScanner.stopScan()
    }
}
