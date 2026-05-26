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

        // Сохранить/восстановить конфиг
        val prefs = requireContext().getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val savedConfig = prefs.getString("config_json", null)
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
