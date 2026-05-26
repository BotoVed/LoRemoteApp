package com.loremote.app.ui

import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.loremote.app.R
import com.loremote.app.protocol.Protocol

class SettingsFragment : Fragment() {

    private var spinnerAdapter: ArrayAdapter<String>? = null
    private val scanResults = mutableListOf<ScanResult>()
    private var spinnerDevices: Spinner? = null
    var tvPingResult: TextView? = null
    private var tvDeviceStatus: TextView? = null

    private var seekRetryCount: SeekBar? = null
    private var tvRetryCountLabel: TextView? = null
    private var seekRetryInterval: SeekBar? = null
    private var tvRetryIntervalLabel: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val main = activity as? MainActivity ?: return

        spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, mutableListOf<String>())
        spinnerAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices = view.findViewById(R.id.spinnerDevices)
        spinnerDevices?.adapter = spinnerAdapter

        tvPingResult = view.findViewById(R.id.tvPingResult)
        tvDeviceStatus = view.findViewById(R.id.tvDeviceStatus)

        seekRetryCount = view.findViewById(R.id.seekRetryCount)
        tvRetryCountLabel = view.findViewById(R.id.tvRetryCountLabel)
        seekRetryInterval = view.findViewById(R.id.seekRetryInterval)
        tvRetryIntervalLabel = view.findViewById(R.id.tvRetryIntervalLabel)

        // Загрузить значения из SharedPreferences
        val prefs = requireContext().getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val retryCount = prefs.getInt("retry_count", 0)
        val retryInterval = prefs.getInt("retry_interval", 30)

        seekRetryCount?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvRetryCountLabel?.text = if (progress == 0) "0 повторов (без повторов)" else "$progress повторов"
                seekRetryInterval?.isEnabled = progress > 0
                seekRetryInterval?.alpha = if (progress > 0) 1f else 0.4f
                prefs.edit().putInt("retry_count", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        seekRetryCount?.progress = retryCount
        if (retryCount > 0) {
            tvRetryCountLabel?.text = "$retryCount повторов"
            seekRetryInterval?.isEnabled = true
            seekRetryInterval?.alpha = 1f
        }

        seekRetryInterval?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val interval = progress + 15
                tvRetryIntervalLabel?.text = "$interval сек"
                prefs.edit().putInt("retry_interval", interval).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        seekRetryInterval?.progress = retryInterval - 15

        // Сохранить/восстановить конфиг
        val configPrefs = requireContext().getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val savedConfig = configPrefs.getString("config_json", null)
        if (!savedConfig.isNullOrBlank()) {
            view.findViewById<EditText>(R.id.etConfig).setText(savedConfig)
        }

        // Сканировать
        (activity as? MainActivity)?.startScan()

        // Подключиться
        view.findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val idx = spinnerDevices?.selectedItemPosition ?: return@setOnClickListener
            val device = scanResults.getOrNull(idx) ?: run {
                Toast.makeText(context, "Выберите устройство", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            main.connectToDevice(device)
            Toast.makeText(context, "Подключение к ${device.device.name}...", Toast.LENGTH_SHORT).show()
        }

        // Применить конфиг
        view.findViewById<Button>(R.id.btnApplyConfig).setOnClickListener {
            val etConfig = view.findViewById<EditText>(R.id.etConfig)
            val text = etConfig.text.toString()
            if (text.isBlank()) {
                Toast.makeText(context, "Введите конфиг", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            main.applyConfig(text)
        }

        // Проверить связь
        view.findViewById<Button>(R.id.btnPing).setOnClickListener {
            tvPingResult?.text = "Отправка PING..."
            main.sendPacket(Protocol.ping())
        }

        updateDeviceList(main.getDeviceList())
        updateConnectState(main)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.startScan()
    }

    fun updateDeviceList(results: List<ScanResult>) {
        scanResults.clear()
        scanResults.addAll(results.sortedBy { it.device.name ?: "z_${it.device.address}" })
        spinnerAdapter?.clear()
        spinnerAdapter?.addAll(scanResults.map { r ->
            "${r.device.name ?: "Unknown"}  ${r.device.address}  ${r.rssi}dBm"
        })
        spinnerAdapter?.notifyDataSetChanged()

        if (results.isNotEmpty()) {
            val first = results[0]
            tvDeviceStatus?.text = "Найдено: ${results.size} (${first.device.name})"
        } else {
            tvDeviceStatus?.text = "Не выбрано"
        }
    }

    fun updateConnectState(main: MainActivity) {
        val state = main.bleManager?.state?.value
        tvDeviceStatus?.text = when (state) {
            is com.loremote.app.ble.BleState.Ready -> "● Подключено"
            is com.loremote.app.ble.BleState.Connecting -> "○ Подключение..."
            is com.loremote.app.ble.BleState.Handshake -> "◑ Инициализация..."
            is com.loremote.app.ble.BleState.Disconnected -> "○ Не подключено"
            is com.loremote.app.ble.BleState.Error -> "✗ ${state.message}"
            else -> "Не выбрано"
        }
    }
}
