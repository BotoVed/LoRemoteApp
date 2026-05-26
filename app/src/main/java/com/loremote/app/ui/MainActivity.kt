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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.loremote.app.R
import com.loremote.app.ble.BleScanner
import com.loremote.app.ble.BleService
import com.loremote.app.ble.BleState
import com.loremote.app.databinding.ActivityMainBinding
 import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
import com.loremote.app.protocol.Protocol
import com.loremote.app.state.DeviceStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
    private var serviceBound = false

   private val deviceList = mutableListOf<ScanResult>()
    private var deviceAdapter: ArrayAdapter<String>? = null
    private var selectedDevice: ScanResult? = null

    fun clearDeviceList() {
        deviceList.clear()
        deviceAdapter?.clear()
        deviceAdapter?.notifyDataSetChanged()
    }

    // Конфиг
    var savedConfig: JSONObject? = null
    var bleService: BleService? = null

    // Текущая вкладка
    private var currentTab = 0

    private val packetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra(BleService.EXTRA_BYTES) ?: return
            runOnUiThread {
                try {
                    val map = Protocol.decode(bytes)
                    handlePacket(map)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Decode error: ${e.message}")
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
            // Авто-подключение после биндинга сервиса
            lifecycleScope.launch {
                delay(500)
                tryAutoConnect()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())

        setupHeader()
        setupBottomNav()
        showTab(0)
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
        tryAutoConnect()
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
        bleService?.scanner?.stop()
    }

    // ── Header ────────────────────────────────────────────────────────────
    private fun setupHeader() {
        binding.tvHomeName.text = "Tech-no-mad"
        binding.ivSettings.setOnClickListener { showTab(1) }
        binding.ivAlertClose.setOnClickListener {
            binding.alertBar.visibility = View.GONE
        }
    }

    fun showAlert(msg: String) {
        binding.tvAlert.text = msg
        binding.alertBar.visibility = View.VISIBLE
    }

    private fun observeBleState() {
        lifecycleScope.launch {
            bleService?.bleManager?.state?.collect { state ->
                when (state) {
                    is BleState.Disconnected -> {
                        binding.ivBleStatus.setColorFilter(getColor(R.color.gray_600))
                    }
                    is BleState.Connecting -> {
                        binding.ivBleStatus.setColorFilter(getColor(R.color.yellow_text))
                    }
                    is BleState.Handshake -> {
                        binding.ivBleStatus.setColorFilter(getColor(R.color.yellow_text))
                    }
                    is BleState.Ready -> {
                        binding.ivBleStatus.setColorFilter(getColor(R.color.green_text))
                        sendPacket(Protocol.ping())
                    }
                    is BleState.Error -> {
                        binding.ivBleStatus.setColorFilter(getColor(R.color.red_text))
                        showAlert("BLE: ${state.message}")
                    }
                    else -> {
                        binding.ivBleStatus.setColorFilter(getColor(R.color.gray_600))
                    }
                }
                // Обновить статус в SettingsFragment
                val settingsFragment = supportFragmentManager.findFragmentById(R.id.contentContainer) as? SettingsFragment
                settingsFragment?.updateConnectState(this@MainActivity)
            }
        }
    }

    private fun updateHaIcon(online: Boolean) {
        binding.ivHaStatus.setColorFilter(
            getColor(if (online) R.color.green_text else R.color.red_text)
        )
    }

    // ── Bottom Nav ────────────────────────────────────────────────────────
    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_control  -> { showTab(0); true }
                R.id.nav_settings -> { showTab(1); true }
                else -> false
            }
        }
    }

    fun showTab(tab: Int) {
        currentTab = tab
        val fragment = when (tab) {
            0 -> ControlFragment()
            else -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.contentContainer, fragment)
            .commit()
    }

    // ── Device List ───────────────────────────────────────────────────────
    private fun updateDeviceList(results: List<ScanResult>) {
        deviceList.clear()
        deviceList.addAll(results)
        deviceAdapter?.clear()
        deviceAdapter?.addAll(results.map { r ->
            val name = r.device.name ?: "Unknown"
            "$name  ${r.device.address}  ${r.rssi}dBm"
        })
        deviceAdapter?.notifyDataSetChanged()

        // Уведомить SettingsFragment
        val settingsFragment = supportFragmentManager.findFragmentById(R.id.contentContainer) as? SettingsFragment
        settingsFragment?.updateDeviceList(results)
    }

    // ── Packet Handler ────────────────────────────────────────────────────
    private fun handlePacket(map: Map<String, Any?>) {
        val tp = (map["tp"] as? Long)?.toInt() ?: return
        val id = map["id"] as? String

        when (tp) {
            PacketType.CONFIRM -> {
                if (id != null) {
                    DeviceStateManager.onConfirmed(id, extractStateValues(map))
                    bleService?.deliveryQueue?.confirm(id)
                }
            }
            PacketType.STATUS -> {
                if (id != null) {
                    DeviceStateManager.onConfirmed(id, extractStateValues(map))
                    (supportFragmentManager.findFragmentById(R.id.contentContainer) as? ControlFragment)
                        ?.onDeviceUpdate(id, map)
                }
            }
            PacketType.PUSH -> {
                if (id != null) {
                    DeviceStateManager.onConfirmed(id, extractStateValues(map))
                    (supportFragmentManager.findFragmentById(R.id.contentContainer) as? ControlFragment)
                        ?.onDeviceUpdate(id, map)
                }
                // Аларм если binary_sensor сработал
                val s = map["s"]
                if (id != null && (s == 1L || s == true)) {
                    val cfg = savedConfig
                    val deviceName = cfg?.optJSONObject("mpg")
                        ?.optJSONObject(id)?.optString("n", id ?: "") ?: (id ?: "")
                    showAlert("⚠️ Тревога: $deviceName")
                    bleService?.showAlarmNotification(deviceName, "Сработал датчик: $deviceName")
                }
            }
            PacketType.CONFIG -> {
                android.util.Log.d("MainActivity", "⚙ CONFIG s=${map["s"]} pg=${map["pg"]}/${map["pgt"]}")
            }
            PacketType.PING -> {
                updateHaIcon(true)
                val cfgh = map["cfgh"] as? String
                (supportFragmentManager.findFragmentById(R.id.contentContainer) as? ControlFragment)
                    ?.onPong(cfgh)
                val pingFragment = supportFragmentManager.findFragmentById(R.id.contentContainer) as? SettingsFragment
                pingFragment?.let {
                    it.tvPingResult?.text = "PONG ✓  cfgh=${cfgh ?: "?"}\n${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                }
            }
        }
    }

    private fun extractStateValues(map: Map<String, Any?>): Map<String, Any?> {
        return listOf("s","bri","ct","th","tc","md","fn","sp","pos","st","v","u")
            .mapNotNull { k -> map[k]?.let { k to it } }
            .toMap()
    }

 // ── Send ──────────────────────────────────────────────────────────────
    fun sendPacket(packet: OutPacket, stateChanges: Map<String, Any?>? = null) {
        val packetWithTs = packet.copy(_ts = System.currentTimeMillis() / 1000)
        lifecycleScope.launch {
            try {
                if (packetWithTs.id != null) {
                    if (stateChanges != null) {
                        DeviceStateManager.onSending(packetWithTs.id, stateChanges)
                    }
                    bleService?.deliveryQueue?.enqueue(packetWithTs.id, packetWithTs)
                } else {
                    // Без id — прямая отправка, без ожидания подтверждения
                    val bytes = Protocol.encode(packetWithTs)
                    bleService?.bleManager?.sendLoRemote(bytes)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Send error: ${e.message}")
            }
        }
    }

    // ── Config ────────────────────────────────────────────────────────────
    fun applyConfig(jsonStr: String) {
        try {
            val cleaned = jsonStr
                .replace(Regex("^.*window\\.LORA_CONFIG\\s*=\\s*"), "")
                .trimEnd(';', ' ', '\n')
            savedConfig = JSONObject(cleaned)
            val homeName = savedConfig?.optString("n", "Tech-no-mad") ?: "Tech-no-mad"
            binding.tvHomeName.text = homeName
            // Сохранить в SharedPreferences — ОРИГИНАЛЬНЫЙ текст
            getSharedPreferences("loremote", Context.MODE_PRIVATE).edit()
                .putString("config_json", jsonStr).apply()
            // Перезаписать конфиг в ControlFragment
            val cf = supportFragmentManager.findFragmentById(R.id.contentContainer) as? ControlFragment
            if (cf != null) {
                cf.buildZones(savedConfig!!)
            }
            Toast.makeText(this, "Конфиг применён ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            savedConfig = null
            Toast.makeText(this, "Ошибка конфига: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Permissions & BLE ────────────────────────────────────────────────
   fun startScan() {
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
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    fun hasBlePermissions(): Boolean {
        return listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun startScanSilent() {
        if (hasBlePermissions()) {
            bleService?.scanner?.start()
        }
    }

    fun connectToDevice(device: ScanResult) {
        bleService?.scanner?.stop()
        getSharedPreferences("loremote", Context.MODE_PRIVATE).edit()
            .putString("last_device_mac", device.device.address)
            .putString("last_device_name", device.device.name ?: "Unknown")
            .apply()
        bleService?.bleManager?.connectTo(device.device)
    }

    fun getDeviceList(): List<ScanResult> = deviceList

    val bleManager get() = bleService?.bleManager

  private fun tryAutoConnect() {
        val prefs = getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val lastMac = prefs.getString("last_device_mac", null)
        val lastName = prefs.getString("last_device_name", null)

        if (lastMac != null && lastName != null) {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter?.isEnabled == true) {
                try {
                    val device = bluetoothAdapter.getRemoteDevice(lastMac)
                    android.util.Log.i("MainActivity", "Auto-connecting to $lastName ($lastMac)")
                    bleService?.bleManager?.connectTo(device)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Auto-connect failed: ${e.message}")
                }
            }
        }
    }

    companion object {
        const val TAG = "MainActivity"
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
