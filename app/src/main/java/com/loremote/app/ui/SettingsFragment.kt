package com.loremote.app.ui

import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.loremote.app.R
 import com.loremote.app.protocol.DeliveryQueue.QueueEntry
import com.loremote.app.protocol.Protocol
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var spinnerAdapter: ArrayAdapter<String>? = null
    private val scanResults = mutableListOf<ScanResult>()
  private var spinnerDevices: Spinner? = null
    var tvPingResult: TextView? = null

    private var seekRetryCount: SeekBar? = null
    private var tvRetryCountLabel: TextView? = null
    private var seekRetryInterval: SeekBar? = null
    private var tvRetryIntervalLabel: TextView? = null
    private var queueListContainer: LinearLayout? = null

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

        queueListContainer = view.findViewById(R.id.queueListContainer)

        // Сохранить/восстановить конфиг
        val configPrefs = requireContext().getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val savedConfig = configPrefs.getString("config_json", null)
        if (!savedConfig.isNullOrBlank()) {
            view.findViewById<EditText>(R.id.etConfig).setText(savedConfig)
        }

        // Сканировать
        (activity as? MainActivity)?.startScan()

       // Подключиться / Отключиться
        view.findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val state = main.bleManager?.state?.value
            if (state is com.loremote.app.ble.BleState.Ready || state is com.loremote.app.ble.BleState.Handshake) {
                disconnectDevice()
                Toast.makeText(context, "Отключено", Toast.LENGTH_SHORT).show()
            } else {
                val idx = spinnerDevices?.selectedItemPosition ?: return@setOnClickListener
                val device = scanResults.getOrNull(idx) ?: run {
                    Toast.makeText(context, "Выберите устройство", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                main.connectToDevice(device)
                Toast.makeText(context, "Подключение к ${device.device.name}...", Toast.LENGTH_SHORT).show()
            }
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
        val main = activity as? MainActivity ?: return
        if (main.hasBlePermissions()) {
            main.startScanSilent()
        }
        view?.post { updateQueueList() }
    }

fun updateDeviceList(results: List<ScanResult>) {
        scanResults.clear()
        scanResults.addAll(results.sortedBy { it.device.name ?: "z_${it.device.address}" })
        spinnerAdapter?.clear()
        spinnerAdapter?.addAll(scanResults.map { r ->
            "${r.device.name ?: "Unknown"}  ${r.device.address}  ${r.rssi}dBm"
        })
        spinnerAdapter?.notifyDataSetChanged()
    }

    fun updateConnectState(main: MainActivity) {
        val state = main.bleManager?.state?.value
        val btn = view?.findViewById<Button>(R.id.btnConnect)
        when (state) {
            is com.loremote.app.ble.BleState.Ready -> {
                btn?.text = "Отключить"
            }
            is com.loremote.app.ble.BleState.Connecting -> {
                btn?.text = "Отключить"
            }
            is com.loremote.app.ble.BleState.Handshake -> {
                btn?.text = "Отключить"
            }
            is com.loremote.app.ble.BleState.Disconnected -> {
                btn?.text = "Подключить"
            }
            is com.loremote.app.ble.BleState.Error -> {
                btn?.text = "Подключить"
            }
            else -> {
                btn?.text = "Подключить"
            }
        }
    }

 fun disconnectDevice() {
        val main = activity as? MainActivity ?: return
        main.bleService?.bleManager?.disconnect()?.enqueue()
        main.bleService?.scanner?.stop()
        main.clearDeviceList()
        main.startScanSilent()
    }

    private fun updateQueueList() {
        val main = activity as? MainActivity ?: return
        val queue = main.bleService?.deliveryQueue ?: return
        val entries = queue.getQueueEntries()

        queueListContainer?.removeAllViews()

        if (entries.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "Очередь пуста"
                textSize = 12f
                setTextColor(getColor(R.color.gray_600))
                gravity = android.view.Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            }
            queueListContainer?.addView(empty)
            return
        }

        entries.forEach { entry ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }

            val name = TextView(requireContext()).apply {
                text = entry.devId
                textSize = 13f
                setTextColor(getColor(R.color.gray_200))
                setPadding(0, 0, dpToPx(16), 0)
            }
            row.addView(name)

            val attempts = TextView(requireContext()).apply {
                text = "${entry.attempts} попыт."
                textSize = 11f
                setTextColor(getColor(R.color.gray_500))
                setPadding(0, 0, dpToPx(16), 0)
            }
            row.addView(attempts)

           val time = TextView(requireContext()).apply {
                val date = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                text = if (entry.lastAttempt != null) date.format(java.util.Date(entry.lastAttempt)) else "—"
                textSize = 11f
                setTextColor(getColor(if (entry.attempts > 3) R.color.red_text else R.color.gray_400))
            }
            row.addView(time)

            queueListContainer?.addView(row)
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun getColor(id: Int) = requireContext().getColor(id)
}
