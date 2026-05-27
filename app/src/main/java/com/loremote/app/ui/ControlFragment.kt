package com.loremote.app.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import android.graphics.drawable.GradientDrawable
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
    private var configJson: JSONObject? = null
    private val zoneExpanded = mutableMapOf<String, Boolean>()
    private val typeExpanded = mutableMapOf<String, Boolean>()

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
        val ar = config.optJSONArray("ar") ?: return
        val mpg = config.optJSONObject("mpg") ?: return
        val prefs = ctx.getSharedPreferences("loremote", Context.MODE_PRIVATE)

        for (i in 0 until ar.length()) {
            val zone = ar.getJSONObject(i)
            val card = buildZoneCard(zone, config)
            zonesContainer?.addView(card)
        }
    }

    private fun buildZoneCard(zone: JSONObject, config: JSONObject): View {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val zoneId = zone.getString("id")
        val zoneName = zone.getString("n")
        val zoneIcon = zone.optString("ic", "home")
        val expanded = prefs.getBoolean("zone_exp_$zoneId", true)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(1, ctx.getColor(R.color.gray_700))
                setCornerRadius(dpToPx(12).toFloat())
                setColor(ctx.getColor(R.color.gray_900))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(10) }
            layoutParams = lp
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(14), dpToPx(12), dpToPx(14))
            isClickable = true
            isFocusable = true
        }

        val iconContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val lp = LinearLayout.LayoutParams(dpToPx(30), dpToPx(30))
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(1, ctx.getColor(R.color.gray_700))
                setCornerRadius(dpToPx(8).toFloat())
                setColor(ctx.getColor(R.color.gray_800))
            }
        }

        val iconView = ImageView(ctx).apply {
            val resId = resolveZoneIcon(zoneIcon)
            setImageResource(resId)
            val lp = LinearLayout.LayoutParams(dpToPx(18), dpToPx(18))
            layoutParams = lp
            setColorFilter(ctx.getColor(R.color.gray_400))
        }
        iconContainer.addView(iconView)
        header.addView(iconContainer)

        val titleBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dpToPx(10), 0, 0, 0)
        }

        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tvName = TextView(ctx).apply {
            text = zoneName
            textSize = 15f
            setTextColor(ctx.getColor(R.color.gray_200))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        nameRow.addView(tvName)

        val devices = getDevicesForZone(zoneId, config)
        val countBadge = TextView(ctx).apply {
            text = devices.size.toString()
            textSize = 11f
            setTextColor(ctx.getColor(R.color.gray_500))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dpToPx(4).toFloat())
                setColor(ctx.getColor(R.color.gray_800))
            }
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
            setGravity(android.view.Gravity.CENTER)
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
        }
        nameRow.addView(countBadge)
        titleBlock.addView(nameRow)

        val summaryRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(5), 0, 0)
        }
        summaryRow.tag = "zone_summary_$zoneId"
        titleBlock.addView(summaryRow)

        header.addView(titleBlock)

        val chevron = TextView(ctx).apply {
            text = "▾"
            textSize = 16f
            setTextColor(ctx.getColor(R.color.gray_500))
            tag = "chevron_$zoneId"
        }
        header.addView(chevron)

        card.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = "zone_body_$zoneId"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (expanded) {
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        val borderTop = View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.gray_700))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
            )
        }
        body.addView(borderTop)
        card.addView(body)

        header.setOnClickListener {
            val newExpanded = !expanded
            zoneExpanded[zoneId] = newExpanded
            body.visibility = if (!newExpanded) View.GONE else View.VISIBLE
            chevron.rotation = if (!newExpanded) 0f else 180f
            prefs.edit().putBoolean("zone_exp_$zoneId", newExpanded).apply()
        }

        return card
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            DeviceStateManager.states.collect { states ->
                states.keys.forEach { hash -> refreshRow(hash) }
            }
        }
    }

    fun refreshRow(hash: String) {
        val row = zonesContainer?.findViewWithTag<LinearLayout>(hash) ?: return
        val state = DeviceStateManager.visible(hash)
        val cfg = configJson?.optJSONObject("mpg")?.optJSONObject(hash) ?: return
        val type = cfg.optString("t", "")

        val leftCol = row.getChildAt(0) as? LinearLayout ?: return
        val subText = leftCol.getChildAt(1) as? TextView
        subText?.text = subText(type, state)

        val rightCol = row.getChildAt(1) as? LinearLayout ?: row
        for (i in 0 until (rightCol as LinearLayout).childCount) {
            val child = rightCol.getChildAt(i)
            if (child is Switch) {
                child.setOnCheckedChangeListener(null)
                child.isChecked = state["s"] == 1L
                child.setOnCheckedChangeListener { _, checked ->
                    val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                    val old = DeviceStateManager.visible(hash)
                    val newVal = mapOf("s" to if (checked) 1L else 0L)
                    main.sendPacket(
                        OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0),
                        oldValue = old,
                        newValue = newVal
                    )
                }
                break
            }
        }

        val inQueue = (activity as? MainActivity)?.bleService?.deliveryQueue
            ?.getQueueEntries()?.any { it.devId == hash } ?: false
        row.alpha = if (inQueue) 0.5f else 1f
        row.isEnabled = !inQueue
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
                        val old = DeviceStateManager.visible(hash)
                        val changes = mapOf("s" to if (isChecked) 1L else 0L)
                        main.sendPacket(
                            OutPacket(tp = PacketType.CMD, id = hash, s = if (isChecked) 1 else 0),
                            oldValue = old,
                            newValue = changes
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
                        val old = DeviceStateManager.visible(hash)
                        val changes = mapOf("s" to if (isChecked) 1L else 0L)
                        val cmd = if (isChecked) "lock" else "unlock"
                        main.sendPacket(
                            OutPacket(tp = PacketType.CMD, id = hash, cmd = cmd),
                            oldValue = old,
                            newValue = changes
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
                val state = DeviceStateManager.visible(hash)
                val pos = toLong(state?.get("pos"))?.toInt() ?: 0
                val st = state?.get("st")
                val tvVal = TextView(ctx).apply {
                    text = if (pos != 0) "открыты·${pos}%" else "закрыты"
                    textSize = 13f
                    setTextColor(getColor(R.color.green_text))
                }
                row.addView(tvVal)
            }
            "SI" -> {
                val state = DeviceStateManager.visible(hash)
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
                val state = DeviceStateManager.visible(hash)
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
                val state = DeviceStateManager.visible(hash)
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
                val active = DeviceStateManager.visible(hash)["s"] == 1L
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
                val state = DeviceStateManager.visible(hash)
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

    fun onPong(cfgh: String?) {
        // PONG получен
    }

    private fun openDevicePopup(hash: String, dev: JSONObject, type: String) {
        val dialog = DevicePopupDialog(hash, dev, type) { packet, changes, old ->
            (activity as? MainActivity)?.sendPacket(packet, oldValue = old, newValue = changes)
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

    private fun divider(): View = View(requireContext()).apply {
        setBackgroundColor(requireContext().getColor(R.color.gray_700))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun getColor(id: Int) = requireContext().getColor(id)

    private fun toLong(v: Any?): Long? = when (v) {
        is Number -> (v as? Number)?.toLong() ?: 0L
        else -> null
    }

    private fun resolveZoneIcon(zoneIcon: String): Int {
        return when (zoneIcon) {
            "home" -> R.drawable.ic_zone_home
            "sofa" -> R.drawable.ic_zone_sofa
            "bed" -> R.drawable.ic_zone_bed
            "kitchen" -> R.drawable.ic_zone_kitchen
            "bathroom" -> R.drawable.ic_zone_bathroom
            "tool" -> R.drawable.ic_zone_tool
            else -> R.drawable.ic_zone_home
        }
    }

    private fun getDevicesForZone(zoneId: String, config: JSONObject): List<Pair<String, JSONObject>> {
        val mpg = config.optJSONObject("mpg") ?: return emptyList()
        val devices = mutableListOf<Pair<String, JSONObject>>()
        mpg.keys().forEach { hash ->
            val dev = mpg.getJSONObject(hash)
            val devArea = dev.optString("a")
            val noArea = devArea == "" || devArea == "null" || dev.opt("a") == null
            if (devArea == zoneId || (noArea && zoneId == "ustroistva")) {
                devices.add(Pair(hash, dev))
            }
        }
        return devices
    }
}
