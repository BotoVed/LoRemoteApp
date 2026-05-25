package com.loremote.app.ui

import android.Manifest
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.loremote.app.ble.BleScanner
import com.loremote.app.ble.BleState
import com.loremote.app.ble.LoRemoteBleManager
import com.loremote.app.databinding.ActivityMainBinding
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
import com.loremote.app.protocol.Protocol
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: BleScanner
    private lateinit var bleManager: LoRemoteBleManager

    private val deviceList = mutableListOf<ScanResult>()
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var selectedDevice: ScanResult? = null
    private var selectedIndex = -1

    private val logLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = BleScanner(this)
        bleManager = LoRemoteBleManager(this) { bytes ->
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

        // Adapter для списка устройств
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.lvDevices.adapter = deviceAdapter

        // Выбор устройства тапом
        binding.lvDevices.setOnItemClickListener { _, view, position, _ ->
            selectedIndex = position
            selectedDevice = deviceList.getOrNull(position)
            // Подсветить выбранное
            for (i in 0 until binding.lvDevices.childCount) {
                binding.lvDevices.getChildAt(i)?.setBackgroundColor(
                    if (i == position) 0xFF2A2A2A.toInt() else android.graphics.Color.TRANSPARENT
                )
            }
            val d = selectedDevice
            addLog("Selected: ${d?.device?.name ?: "Unknown"} (${d?.device?.address})")
        }

        // Статус BLE
        lifecycleScope.launch {
            bleManager.state.collect { state ->
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

        // Обновление списка устройств
        lifecycleScope.launch {
            scanner.results.collect { results ->
                deviceList.clear()
                deviceList.addAll(results)
                deviceAdapter.clear()
                deviceAdapter.addAll(results.map { r ->
                    val name = r.device.name ?: "Unknown"
                    "$name  ${r.device.address}  ${r.rssi}dBm"
                })
                deviceAdapter.notifyDataSetChanged()
            }
        }

        // Кнопки
        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }

        binding.btnConnect.setOnClickListener {
            val device = selectedDevice ?: run {
                Toast.makeText(this, "Выберите устройство из списка", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scanner.stop()
            addLog("Connecting to ${device.device.name}...")
            bleManager.connectTo(device.device)
        }

        binding.btnPing.setOnClickListener { sendPacket(Protocol.ping()) }
        binding.btnAll.setOnClickListener { sendPacket(Protocol.requestAll()) }
        binding.btnDisc.setOnClickListener {
            bleManager.disconnect().enqueue()
            addLog("Disconnecting...")
        }
    }

    private fun sendPacket(packet: OutPacket) {
        lifecycleScope.launch {
            try {
                val bytes = Protocol.encode(packet)
                bleManager.sendLoRemote(bytes)
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
            scanner.start()
            addLog("Scanning...")
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) scanner.start()
        else Toast.makeText(this, "BLE permissions required", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        bleManager.disconnect().enqueue()
    }
}
