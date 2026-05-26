package com.loremote.app.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.loremote.app.R
import kotlinx.coroutines.launch
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
import com.loremote.app.state.DeviceStateManager
import com.loremote.app.state.DeviceStatus
import org.json.JSONObject

class ControlFragment : Fragment() {

    private var zonesContainer: LinearLayout? = null
    private val devStates = mutableMapOf<String, Map<String, Any?>>()
    private var configJson: JSONObject? = null

     override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(requireContext().getColor(R.color.bg_dark))
        }
        zonesContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        scroll.addView(zonesContainer)

        return scroll
    }

    override fun onResume() {
        super.onResume()
        val prefs = requireContext().getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val saved = prefs.getString("config_json", null)
        Log.d("ControlFragment", "onResume: saved=${saved?.take(100)}")
        Log.d("ControlFragment", "onResume: container=${zonesContainer != null}, childCount=${zonesContainer?.childCount}")

        if (saved == null) {
            Log.d("ControlFragment", "No config — showing placeholder")
            showPlaceholder("Конфигурация не загружена")
            return
        }
        try {
            val cleaned = saved
                .replace(Regex("^.*window\\.LORA_CONFIG\\s*=\\s*"), "")
                .trimEnd(';', ' ', '\n')
            Log.d("ControlFragment", "cleaned=${cleaned.take(100)}")
            val json = org.json.JSONObject(cleaned)
            Log.d("ControlFragment", "json ok, ar=${json.optJSONArray("ar")?.length()}, mpg=${json.optJSONObject("mpg")?.length()}")
            buildZones(json)
            Log.d("ControlFragment", "buildZones done, childCount=${zonesContainer?.childCount}")
        } catch (e: Exception) {
            Log.e("ControlFragment", "Error: ${e.message}", e)
            showPlaceholder("Ошибка: ${e.message}")
        }
    }

    private fun showPlaceholder(message: String) {
        zonesContainer?.removeAllViews()
        val ctx = requireContext()
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dpToPx(32), dpToPx(80), dpToPx(32), dpToPx(32))
        }
        wrapper.addView(TextView(ctx).apply {
            text = "⚙️"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
        })
        wrapper.addView(TextView(ctx).apply {
            text = message
            textSize = 14f
            setTextColor(ctx.getColor(R.color.gray_500))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dpToPx(12), 0, 0)
        })
        wrapper.addView(TextView(ctx).apply {
            text = "Перейдите в Настройки и вставьте window.LORA_CONFIG"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_600))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dpToPx(8), 0, 0)
        })
        zonesContainer?.addView(wrapper)
    }

    fun buildZones(config: JSONObject) {
        configJson = config
        zonesContainer?.removeAllViews() ?: return
        val ctx = requireContext()
        val areas = config.optJSONArray("ar") ?: return
        val mpg = config.optJSONObject("mpg") ?: return

        var hasAny = false
        Log.d("ControlFragment", "buildZones: ar=${areas.length()}, mpg=${mpg.length()}")
        for (i in 0 until areas.length()) {
            val area = areas.getJSONObject(i)
            val areaId = area.getString("id")
            val areaName = area.getString("n")
            Log.d("ControlFragment", "  area=$areaId ($areaName)")

            val devices = mutableListOf<Pair<String, JSONObject>>()
            mpg.keys().forEach { hash ->
                val dev = mpg.getJSONObject(hash)
                val devArea = dev.optString("a")
                val noArea = devArea == "" || devArea == "null"
                Log.d("ControlFragment", "    device=$hash a='$devArea' noArea=$noArea -> match=${devArea == areaId || (noArea && areaId == "ustroistva")}")
                if (devArea == areaId || (noArea && areaId == "ustroistva")) {
                    devices.add(Pair(hash, dev))
                }
            }
            Log.d("ControlFragment", "  area $areaId: ${devices.size} devices")
            if (devices.isEmpty()) continue
            hasAny = true
            val zoneCard = buildZoneCard(areaName, devices, mpg)
            zonesContainer?.addView(zoneCard)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            DeviceStateManager.states.collect { states ->
                states.forEach { (hash, state) ->
                    updateDeviceRow(hash, state)
                }
            }
        }
    }

    fun updateDeviceRow(hash: String, state: com.loremote.app.state.DeviceState) {
        val row = zonesContainer?.findViewWithTag<LinearLayout>(hash) ?: return

        applyRowStatus(row, state.status)

        val visible = DeviceStateManager.visible(hash)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child is Switch) {
                child.setOnCheckedChangeListener(null)
                child.isChecked = visible["s"] == 1L
                child.setOnCheckedChangeListener { _, checked ->
                    val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                    val changes = mapOf("s" to if (checked) 1L else 0L)
                    main.sendPacket(
                        OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0),
                        stateChanges = changes
                    )
                }
                break
            }
        }
    }

    private fun applyRowStatus(row: View, status: DeviceStatus) {
        when (status) {
            DeviceStatus.PENDING -> {
                (row as? LinearLayout)?.alpha = 0.5f
            }
            DeviceStatus.FAILED -> {
                (row as? LinearLayout)?.setBackgroundColor(0x15F87171.toInt())
            }
            DeviceStatus.OK -> {
                (row as? LinearLayout)?.alpha = 1f
                (row as? LinearLayout)?.setBackgroundColor(0)
            }
        }
    }

    private fun buildZoneCard(zoneName: String, devices: List<Pair<String, JSONObject>>, mpg: JSONObject): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_zone_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(10) }
            layoutParams = lp
        }

        val header = TextView(ctx).apply {
            text = zoneName
            textSize = 11f
            setTextColor(ctx.getColor(R.color.gray_400))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setAllCaps(true)
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10))
        }
        card.addView(header)

        card.addView(divider())

        val byType = devices.groupBy { it.second.optString("t", "?") }
        val typeOrder = listOf("L","SW","C","WH","F","CV","LK","BS","S","SI","A","H","B")

        typeOrder.filter { byType.containsKey(it) }.forEach { type ->
            val typeDevices = byType[type] ?: return@forEach
            val typeSection = buildTypeSection(type, typeDevices)
            card.addView(typeSection)
        }

        return card
    }

    private fun buildTypeSection(type: String, devices: List<Pair<String, JSONObject>>): View {
        val ctx = requireContext()
        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val typeName = getTypeName(type)
        if (typeName != null) {
            val label = TextView(ctx).apply {
                text = typeName
                textSize = 11f
                setTextColor(ctx.getColor(R.color.gray_400))
                setTextSize(11f)
                letterSpacing = 0.08f
                setAllCaps(true)
                gravity = android.view.Gravity.CENTER
                setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(4))
            }
            section.addView(label)
        }

        val devList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(10))
        }

        devices.forEach { (hash, dev) ->
            val row = buildDeviceRow(hash, dev, type)
            devList.addView(row)
        }

        section.addView(devList)
        return section
    }

    private fun buildDeviceRow(hash: String, dev: JSONObject, type: String): View {
        val ctx = requireContext()
        val state = DeviceStateManager.visible(hash)
        val deviceStatus = DeviceStateManager.get(hash)?.status ?: DeviceStatus.OK

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8))
            tag = hash
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        applyRowStatus(row, deviceStatus)

        val nameBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(ctx).apply {
            text = dev.optString("n", hash)
            textSize = 14f
            setTextColor(getColor(R.color.gray_200))
        }
        val tvSub = TextView(ctx).apply {
            id = View.generateViewId()
            text = subText(type, state)
            textSize = 11f
            setTextColor(getColor(R.color.gray_500))
        }
        nameBlock.addView(tvName)
        nameBlock.addView(tvSub)
        row.addView(nameBlock)

        when (type) {
            "L", "SW", "C", "WH", "F", "H" -> {
                val toggle = Switch(ctx).apply {
                    isChecked = state["s"] == 1L
                    setOnCheckedChangeListener { _, isChecked ->
                        val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                        val changes = mapOf("s" to if (isChecked) 1L else 0L)
                        main.sendPacket(
                            OutPacket(tp = PacketType.CMD, id = hash, s = if (isChecked) 1 else 0),
                            stateChanges = changes
                        )
                    }
                }
                row.addView(toggle)
            }
            "LK" -> {
                val toggle = Switch(ctx).apply {
                    isChecked = state["s"] == 1L
                    setOnCheckedChangeListener { _, isChecked ->
                        val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                        val changes = mapOf("s" to if (isChecked) 1L else 0L)
                        val cmd = if (isChecked) "lock" else "unlock"
                        main.sendPacket(
                            OutPacket(tp = PacketType.CMD, id = hash, cmd = cmd),
                            stateChanges = changes
                        )
                    }
                }
                row.addView(toggle)
            }
            "B" -> {
                val btn = Button(ctx).apply {
                    text = "▶"
                    setTextSize(16f)
                    setOnClickListener {
                        val main = activity as? MainActivity ?: return@setOnClickListener
                        main.sendPacket(OutPacket(tp = PacketType.CMD, id = hash, cmd = "press"))
                    }
                }
                row.addView(btn)
            }
            "CV" -> {
                val state = devStates[hash]
                val pos = (state?.get("pos") as? Long)?.toInt() ?: 0
                val st = state?.get("st")
                val tvVal = TextView(ctx).apply {
                    text = if (pos != 0) "открыты·${pos}%" else "закрыты"
                    textSize = 13f
                    setTextColor(getColor(R.color.green_text))
                }
                row.addView(tvVal)
            }
            "SI" -> {
                val state = devStates[hash]
                val v = state?.get("v")
                val u = dev.optString("u", "")
                val tvVal = TextView(ctx).apply {
                    text = if (v != null) "$v$u" else "—"
                    textSize = 13f
                    setTextColor(getColor(if (v != null) R.color.green_text else R.color.gray_500))
                }
                row.addView(tvVal)
            }
            "A" -> {
                val state = devStates[hash]
                val mode = state?.get("s")
                val tvBadge = TextView(ctx).apply {
                    text = when (mode) {
                        1L -> "armed"
                        2L -> "stay"
                        3L -> "night"
                        else -> "disarmed"
                    }
                    textSize = 12f
                    setTextColor(getColor(R.color.yellow_text))
                    setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    setBackgroundResource(R.drawable.badge_yellow)
                }
                row.addView(tvBadge)
            }
            "S" -> {
                val state = devStates[hash]
                val v = state?.get("v")
                val u = dev.optString("u", "")
                val tvVal = TextView(ctx).apply {
                    text = if (v != null) "$v$u" else "—"
                    textSize = 13f
                    setTextColor(getColor(R.color.green_text))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                row.addView(tvVal)
            }
            "BS" -> {
                val active = devStates[hash]?.get("s") == 1L
                val tvBadge = TextView(ctx).apply {
                    text = if (active) "⚠️ Тревога" else "✓ Норма"
                    textSize = 12f
                    setTextColor(getColor(if (active) R.color.red_text else R.color.green_text))
                    setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    setBackgroundResource(if (active) R.drawable.badge_red else R.drawable.badge_green)
                }
                row.addView(tvBadge)
            }
            else -> {
                val state = devStates[hash]
                val s = state?.get("s")
                if (s is Long && s != 0L) {
                    val tvVal = TextView(ctx).apply {
                        text = s.toString()
                        textSize = 13f
                        setTextColor(getColor(R.color.green_text))
                    }
                    row.addView(tvVal)
                }
            }
        }

        row.setOnLongClickListener {
            openDevicePopup(hash, dev, type)
            true
        }

        row.setOnClickListener {
            val popupTypes = listOf("L", "SW", "C", "WH", "F", "H", "CV", "SI", "A", "S", "BS")
            if (popupTypes.contains(type)) {
                openDevicePopup(hash, dev, type)
            }
        }

        return row
    }

    fun onDeviceUpdate(id: String, map: Map<String, Any?>) {
        devStates[id] = map
        activity?.runOnUiThread {
            refreshRow(id)
        }
    }

    fun onPong(cfgh: String?) {
        // PONG получен
    }

    private fun openDevicePopup(hash: String, dev: JSONObject, type: String) {
        val dialog = DevicePopupDialog(hash, dev, type) { packet, changes ->
            (activity as? MainActivity)?.sendPacket(packet, changes)
        }
        dialog.show(parentFragmentManager, "device_popup")
    }

    private fun getTypeName(type: String): String? {
        return when (type) {
            "L" -> "СВЕТ"
            "SW", "SI" -> "ПЕРЕКЛЮЧАТЕЛИ"
            "C" -> "КЛИМАТ"
            "WH" -> "ВОДОНАГРЕВАТЕЛЬ"
            "F" -> "ВЕНТИЛЯЦИЯ"
            "H" -> "УВЛАЖНЕНИЕ"
            "CV" -> "ЖАЛЮЗИ"
            "LK" -> "ЗАМКИ"
            "A" -> "БЕЗОПАСНОСТЬ"
            "BS", "S" -> "ДАТЧИКИ"
            "B" -> "КНОПКИ И СЦЕНЫ"
            else -> null
        }
    }

    private fun subText(type: String, state: Map<String, Any?>?): String {
        if (state == null) return "—"
        return when (type) {
            "L"  -> if (state["s"] == 1L) "${state["bri"] ?: 0}% · ${(state["ct"] as? Number)?.toInt() ?: 0}K" else "выкл"
            "SW" -> if (state["s"] == 1L) "включён" else "выкл"
            "C"  -> if (state["s"] == 1L) {
                val th = (state["th"] as? Number)?.toInt() ?: 0
                val tc = (state["tc"] as? Number)?.toInt() ?: 0
                val mode = (state["mode"] as? String) ?: ""
                "→$th° · $tc° · $mode"
            } else "выкл"
            "WH" -> if (state["s"] == 1L) {
                val th = (state["th"] as? Number)?.toInt() ?: 0
                val tc = (state["tc"] as? Number)?.toInt() ?: 0
                "→$th° · сейчас $tc°"
            } else "выкл"
            "F"  -> if (state["s"] == 1L) "${state["speed"] ?: 0}%" else "выкл"
            "H"  -> if (state["s"] == 1L) {
                val th = (state["th"] as? Number)?.toInt() ?: 0
                val tc = (state["tc"] as? Number)?.toInt() ?: 0
                "→$th% · сейчас $tc%"
            } else "выкл"
            "CV" -> ""
            "LK" -> if (state["s"] == 1L) "locked" else "unlocked"
            "BS" -> if (state["s"] == 1L) "Сработал" else "Норма"
            "SI" -> state["v"]?.toString() ?: "—"
            "A"  -> when (state["s"]) {
                1L -> "armed"
                2L -> "stay"
                3L -> "night"
                else -> "disarmed"
            }
            "S"  -> state["v"]?.toString() ?: "—"
            else -> ""
        }
    }

    private fun refreshRow(id: String) {
        // Проходим по всем карточкам и ищем устройство с данным id
        val count = zonesContainer?.childCount ?: 0
        for (i in 0 until count) {
            val zoneCard = zonesContainer?.getChildAt(i) ?: continue
            if (zoneCard is LinearLayout) {
                val childCount = zoneCard.childCount
                for (j in 1 until childCount) {
                    val child = zoneCard.getChildAt(j)
                    refreshViewRecursive(child, id)
                }
            }
        }
    }

    private fun refreshViewRecursive(view: View, id: String) {
        if (view is LinearLayout) {
            val count = view.childCount
            for (i in 0 until count) {
                refreshViewRecursive(view.getChildAt(i), id)
            }
        } else if (view is TextView) {
            // Попытка найти имя устройства в TextView
            val parent = view.parent
            if (parent is LinearLayout) {
                val nameView = parent.getChildAt(0)
                if (nameView is TextView) {
                    // Проверяем, совпадает ли имя с устройством
                    val dev = findDeviceByName(nameView.text.toString())
                    if (dev != null) {
                        val hash = dev
                        val state = devStates[dev]
                        if (state != null) {
                            // Обновить подзаголовок
                            val subView = parent.getChildAt(1)
                            if (subView is TextView) {
                                // Нужно определить тип — упрощаем: перестраиваем
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findDeviceByName(name: String): String? {
        val config = configJson ?: return null
        val mpg = config.optJSONObject("mpg") ?: return null
        mpg.keys().forEach { hash ->
            val dev = mpg.getJSONObject(hash)
            if (dev.optString("n", "") == name) return hash
        }
        return null
    }

    private fun divider(): View = View(requireContext()).apply {
        setBackgroundColor(requireContext().getColor(R.color.gray_700))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun getColor(id: Int) = requireContext().getColor(id)
}
