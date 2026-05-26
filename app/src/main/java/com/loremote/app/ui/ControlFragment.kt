package com.loremote.app.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.loremote.app.R
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
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

        // Восстановить конфиг если есть
        (activity as? MainActivity)?.let { main ->
            main.savedConfig?.let { buildZones(it) }
        }

        return scroll
    }

    fun buildZones(config: JSONObject) {
        configJson = config
        zonesContainer?.removeAllViews() ?: return
        val ctx = requireContext()
        val areas = config.optJSONArray("ar") ?: return
        val mpg = config.optJSONObject("mpg") ?: return

        for (i in 0 until areas.length()) {
            val area = areas.getJSONObject(i)
            val areaId = area.getString("id")
            val areaName = area.getString("n")

            val devices = mutableListOf<Pair<String, JSONObject>>()
            mpg.keys().forEach { hash ->
                val dev = mpg.getJSONObject(hash)
                if (dev.optString("a") == areaId) {
                    devices.add(Pair(hash, dev))
                }
            }
            if (devices.isEmpty()) return

            val zoneCard = buildZoneCard(areaName, devices, mpg)
            zonesContainer?.addView(zoneCard)
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

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
        }
        val tvName = TextView(ctx).apply {
            text = zoneName
            textSize = 15f
            setTextColor(getColor(R.color.gray_200))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvName)
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
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

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
            text = subText(type, devStates[hash])
            textSize = 11f
            setTextColor(getColor(R.color.gray_500))
        }
        nameBlock.addView(tvName)
        nameBlock.addView(tvSub)
        row.addView(nameBlock)

        when (type) {
            "L", "SW", "C", "WH", "F", "H" -> {
                val toggle = Switch(ctx).apply {
                    isChecked = devStates[hash]?.get("s") == 1L
                    setOnCheckedChangeListener { _, isChecked ->
                        val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                        main.sendPacket(OutPacket(tp = PacketType.CMD, id = hash, s = if (isChecked) 1 else 0))
                    }
                }
                row.addView(toggle)
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
                    text = if (active) "Тревога!" else "Норма"
                    textSize = 12f
                    setTextColor(getColor(if (active) R.color.red_text else R.color.green_text))
                    setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    setBackgroundResource(if (active) R.drawable.badge_red else R.drawable.badge_green)
                }
                row.addView(tvBadge)
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
        val dialog = DevicePopupDialog(hash, dev, type, devStates[hash]) { packet ->
            (activity as? MainActivity)?.sendPacket(packet)
        }
        dialog.show(parentFragmentManager, "device_popup")
    }

    private fun subText(type: String, state: Map<String, Any?>?): String {
        if (state == null) return "—"
        return when (type) {
            "L"  -> if (state["s"] == 1L) "${state["bri"] ?: 0}% · ${state["ct"] ?: 0}K" else "выкл"
            "SW" -> if (state["s"] == 1L) "включён" else "выкл"
            "C"  -> if (state["s"] == 1L) "→${(state["th"] as? Number)?.toInt() ?: 0}°C" else "выкл"
            "WH" -> if (state["s"] == 1L) "→${(state["th"] as? Number)?.toInt() ?: 0}°C" else "выкл"
            "CV" -> "${state["st"] ?: "?"} · ${state["pos"] ?: 0}%"
            "LK" -> "${state["s"] ?: "?"}"
            "BS" -> if (state["s"] == 1L) "Сработал" else "Норма"
            "SI" -> state["v"]?.toString() ?: "—"
            "H"  -> if (state["s"] == 1L) "включён" else "выкл"
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
