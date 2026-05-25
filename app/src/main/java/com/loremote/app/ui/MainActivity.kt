package com.loremote.app.ui

  import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.loremote.app.ble.BleScanner
import com.loremote.app.ble.BleService
import com.loremote.app.ble.BleState
import com.loremote.app.ble.GATEWAY_NODE_NUM
import com.loremote.app.ble.LOREMOTE_PORT
import com.loremote.app.databinding.ActivityMainBinding
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
import com.loremote.app.protocol.Protocol
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var bleService: BleService? = null
    private var serviceBound = false

    private val deviceList = mutableListOf<ScanResult>()
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var selectedDevice: ScanResult? = null
    private var selectedIndex = -1

    private val logLines = mutableListOf<String>()

    private val packetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra(BleService.EXTRA_BYTES) ?: return
            runOnUiThread {
                try {
                    val map = Protocol.decode(bytes)
                    addLog("RX tp:${map["tp"]} id:${map["id"]} s:${map["s"]} v:${map["v"]}")
                    handlePacket(map)
                } catch (e: Exception) {
                    addLog("RX raw ${bytes.size}b: ${bytes.take(8).joinToString("") { "%02x".format(it) }}...")
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bleService = (binder as BleService.LocalBinder).getService()
            serviceBound = true
            observeBleState()
            lifecycleScope.launch {
                bleService!!.scanner.results.collect { results ->
                    updateDeviceList(results)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            bleService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.lvDevices.adapter = deviceAdapter

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        binding.lvDevices.setOnItemClickListener { _, view, position, _ ->
            selectedIndex = position
            selectedDevice = deviceList.getOrNull(position)
            for (i in 0 until binding.lvDevices.childCount) {
                binding.lvDevices.getChildAt(i)?.setBackgroundColor(
                    if (i == position) 0xFF2A2A2A.toInt() else android.graphics.Color.TRANSPARENT
                )
            }
            val d = selectedDevice
            addLog("Selected: ${d?.device?.name ?: "Unknown"} (${d?.device?.address})")
        }

        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }

        binding.btnConnect.setOnClickListener {
            val device = selectedDevice ?: run {
                Toast.makeText(this, "Выберите устройство из списка", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scannerStop()
            addLog("Connecting to ${device.device.name}...")
            bleService?.bleManager?.connectTo(device.device)
        }

        binding.btnPing.setOnClickListener { sendPacket(Protocol.ping()) }
        binding.btnPingBroadcast.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val bytes = Protocol.encode(Protocol.ping())
                    bleService?.bleManager?.sendLoRemoteBroadcast(bytes)
                    addLog("TX→ broadcast tp:6 (${bytes.size}b)")
                } catch (e: Exception) {
                    addLog("TX→ ERROR: ${e.message}")
                }
            }
        }
        binding.btnAll.setOnClickListener { sendPacket(Protocol.requestAll()) }
        binding.btnDisc.setOnClickListener {
            bleService?.bleManager?.disconnect()?.enqueue()
            addLog("Disconnecting...")
        }
        binding.btnSendTest.setOnClickListener { sendTestPacket() }
        binding.btnSendText.setOnClickListener { sendTextPacket() }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, BleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        registerReceiver(
            packetReceiver,
            IntentFilter(BleService.ACTION_PACKET),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        unregisterReceiver(packetReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        scannerStop()
    }

    private fun scannerStop() {
        bleService?.scanner?.stop()
    }

    private fun observeBleState() {
        lifecycleScope.launch {
            bleService?.bleManager?.state?.collect { state ->
                val txt = when (state) {
                    is BleState.Disconnected -> "○ Disconnected"
                    is BleState.Connecting   -> "○ Connecting..."
                    is BleState.Handshake    -> "◑ Handshake..."
                    is BleState.Ready        -> "● Ready"
                    is BleState.Error        -> "✗ ${state.message}"
                }
                binding.tvStatus.text = txt
                if (state is BleState.Ready) {
                    addLog("✓ Connected and ready!")
                    sendPacket(Protocol.ping())
                }
                if (state is BleState.Error) {
                    addLog("ERROR: ${state.message}")
                }
            }
        }
    }

    private fun updateDeviceList(results: List<ScanResult>) {
        deviceList.clear()
        deviceList.addAll(results)
        deviceAdapter.clear()
        deviceAdapter.addAll(results.map { r ->
            val name = r.device.name ?: "Unknown"
            "$name  ${r.device.address}  ${r.rssi}dBm"
        })
        deviceAdapter.notifyDataSetChanged()
    }

    private fun sendPacket(packet: OutPacket) {
        lifecycleScope.launch {
            try {
                val bytes = Protocol.encode(packet)
                bleService?.bleManager?.sendLoRemote(bytes)
                addLog("TX tp:${packet.tp} (${bytes.size}b)")
            } catch (e: Exception) {
                addLog("TX ERROR: ${e.message}")
            }
        }
    }

    private fun handlePacket(map: Map<String, Any?>) {
        val tp = (map["tp"] as? Long)?.toInt() ?: return
        when (tp) {
            PacketType.CONFIRM -> addLog("✓ CONFIRM id=${map["id"]}")
            PacketType.STATUS  -> addLog("≡ STATUS  id=${map["id"]} s=${map["s"]}")
            PacketType.PUSH    -> addLog("↓ PUSH    id=${map["id"]} s=${map["s"]} v=${map["v"]}")
            PacketType.CONFIG  -> addLog("⚙ CONFIG  s=${map["s"]} pg=${map["pg"]}/${map["pgt"]}")
            PacketType.PING    -> addLog("♥ PONG    cfgh=${map["cfgh"]}")
        }
    }

    private fun addLog(line: String) {
        Log.d(TAG, line)
        logLines.add(0, line)
        if (logLines.size > 100) logLines.removeAt(logLines.size - 1)
        binding.tvLog.text = logLines.joinToString("\n")
        binding.scrollLog.post { binding.scrollLog.smoothScrollTo(0, 0) }
    }

    private fun sendTestPacket() {
        lifecycleScope.launch {
            try {
                val bytes = Protocol.encode(Protocol.ping())
                bleService?.bleManager?.sendLoRemotePacket(bytes, GATEWAY_NODE_NUM, 256)
                addLog("TX→TEST tp:6 (${bytes.size}b)")
            } catch (e: Exception) {
                addLog("TX→TEST ERROR: ${e.message}")
            }
        }
    }

    private fun sendTextPacket() {
        lifecycleScope.launch {
            try {
                val textBytes = "hello".toByteArray()
                bleService?.bleManager?.sendLoRemotePacket(textBytes, GATEWAY_NODE_NUM, 1)
                addLog("TX→TEXT hello (${textBytes.size}b)")
            } catch (e: Exception) {
                addLog("TX→TEXT ERROR: ${e.message}")
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            bleService?.scanner?.start()
            addLog("Scanning...")
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            bleService?.scanner?.start()
        } else {
            Toast.makeText(this, "BLE permissions required", Toast.LENGTH_LONG).show()
        }
    }
}
