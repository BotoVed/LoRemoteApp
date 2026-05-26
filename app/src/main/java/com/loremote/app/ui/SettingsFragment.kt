package com.loremote.app.ui

import android.bluetooth.le.ScanResult
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

        view.findViewById<Button>(R.id.btnScan).setOnClickListener {
            main.startScan()
            Toast.makeText(context, "Сканирование...", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val idx = spinnerDevices?.selectedItemPosition ?: return@setOnClickListener
            val device = scanResults.getOrNull(idx) ?: run {
                Toast.makeText(context, "Выберите устройство", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            main.connectToDevice(device)
            Toast.makeText(context, "Подключение к ${device.device.name}...", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnApplyConfig).setOnClickListener {
            val text = view.findViewById<EditText>(R.id.etConfig).text.toString()
            if (text.isBlank()) {
                Toast.makeText(context, "Введите конфиг", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            main.applyConfig(text)
        }

        view.findViewById<Button>(R.id.btnPing).setOnClickListener {
            tvPingResult?.text = "Отправка PING..."
            main.sendPacket(Protocol.ping())
        }

        updateDeviceList(main.getDeviceList())
    }

    fun updateDeviceList(results: List<ScanResult>) {
        scanResults.clear()
        scanResults.addAll(results)
        spinnerAdapter?.clear()
        spinnerAdapter?.addAll(results.map { r ->
            "${r.device.name ?: "Unknown"}  ${r.device.address}  ${r.rssi}dBm"
        })
        spinnerAdapter?.notifyDataSetChanged()
    }
}
