package com.loremote.app.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.loremote.app.state.DisplayStateManager
import org.json.JSONObject

data class PillData(val text: String, val type: PillType, val icon: String)
enum class PillType { OK, WARN, ERR, BLUE, NEUTRAL }

class ControlFragment : Fragment() {

    private var zonesContainer: LinearLayout? = null
    private var configJson: JSONObject? = null
    private val zoneExpanded = mutableMapOf<String, Boolean>()
    private val typeExpanded = mutableMapOf<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

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

        // Collect devices without a zone
        val unassignedDevices = mutableListOf<Pair<String, JSONObject>>()
        mpg.keys().forEach { hash ->
            val dev = mpg.getJSONObject(hash)
            val devArea = dev.optString("a")
            val noArea = devArea == "" || devArea == "null" || dev.opt("a") == null
            if (noArea) {
                unassignedDevices.add(Pair(hash, dev))
            }
        }

        for (i in 0 until ar.length()) {
            val zone = ar.getJSONObject(i)
            val zoneId = zone.optString("id")
            if (zoneId == "ustroistva") continue
            val devices = getDevicesForZone(zoneId, config, emptyList())
            if (devices.isEmpty()) continue
            val card = buildZoneCard(zone, config, unassignedDevices)
            zonesContainer?.addView(card)
        }

        val unassigned = getDevicesForZone("ustroistva", config, emptyList())
        if (unassigned.isNotEmpty()) {
            val sysZone = JSONObject()
                .put("id", "ustroistva")
                .put("n", "")
                .put("ic", "devices")
                .put("ord", 99)
            val card = buildZoneCard(sysZone, config, unassigned)
            zonesContainer?.addView(card)
            refreshZonePills("ustroistva")
        }

        for (i in 0 until ar.length()) {
            val zone = ar.getJSONObject(i)
            refreshZonePills(zone.optString("id"))
        }
    }

    private fun buildZoneCard(zone: JSONObject, config: JSONObject, unassignedDevices: List<Pair<String, JSONObject>> = emptyList()): View {
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

        val devices = getDevicesForZone(zoneId, config, unassignedDevices)
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

        // Build type sections
        val byType = devices.groupBy { it.second.optString("t", "?") }
        val typeOrder = listOf("L", "SW", "C", "WH", "F", "CV", "LK", "BS", "S", "SI", "A", "H", "B")

        val sortedTypes = typeOrder.filter { byType.containsKey(it) }
        for (tIdx in sortedTypes.indices) {
            val type = sortedTypes[tIdx]
            val typeDevices = byType[type] ?: continue
            val typeSection = buildTypeSection(zoneId, type, typeDevices, tIdx < sortedTypes.size - 1)
            body.addView(typeSection)
        }

  header.setOnClickListener {
        val isCurrentlyExpanded = zoneExpanded[zoneId] ?: expanded
        val newExpanded = !isCurrentlyExpanded
        zoneExpanded[zoneId] = newExpanded
        body.visibility = if (newExpanded) View.VISIBLE else View.GONE
        chevron.rotation = if (newExpanded) 180f else 0f
        prefs.edit().putBoolean("zone_exp_$zoneId", newExpanded).apply()
    }

        return card
    }

    private fun buildTypeSection(zoneId: String, type: String, devices: List<Pair<String, JSONObject>>, hasBorder: Boolean): View {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("loremote", Context.MODE_PRIVATE)
        val key = "$zoneId" + "_" + type
        val expanded = prefs.getBoolean("type_exp_$key", true)

        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (hasBorder) {
                val bottomBorder = View(ctx).apply {
                    setBackgroundColor(ctx.getColor(R.color.gray_700))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                    )
                }
                addView(bottomBorder)
            }
        }

        val typeIconRes = resolveTypeIcon(type)
        val typeName = getTypeName(type)

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(9), dpToPx(14), dpToPx(9), dpToPx(14))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dpToPx(8).toFloat())
                setColor(ctx.getColor(R.color.gray_800))
            }
        }

        val iconView = ImageView(ctx).apply {
            setImageResource(typeIconRes)
            val lp = LinearLayout.LayoutParams(dpToPx(16), dpToPx(16))
            layoutParams = lp
            setColorFilter(ctx.getColor(R.color.gray_500))
        }
        header.addView(iconView)

        val nameTv = TextView(ctx).apply {
            text = typeName
            textSize = 13f
            setTextColor(ctx.getColor(R.color.gray_500))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dpToPx(7)
            layoutParams = lp
        }
        header.addView(nameTv)

        val countBadge = TextView(ctx).apply {
            text = "×${devices.size}"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.gray_500))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dpToPx(99).toFloat())
                setColor(ctx.getColor(R.color.gray_900))
            }
            setPadding(dpToPx(7), dpToPx(2), dpToPx(7), dpToPx(2))
        }
        header.addView(countBadge)

        val chevron = TextView(ctx).apply {
            text = "▾"
            textSize = 14f
            setTextColor(ctx.getColor(R.color.gray_500))
            val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(0, 0)
            lp.setMarginStart(dpToPx(8))
            layoutParams = lp
            tag = "tc_$key"
        }
        header.addView(chevron)

        section.addView(header)

        val devicesList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), dpToPx(14), dpToPx(8), dpToPx(10))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }

        for (i in devices.indices) {
            val (hash, dev) = devices[i]
            val row = buildDeviceRow(hash, dev, type)
            devicesList.addView(row)
            if (i < devices.size - 1) {
                devicesList.addView(createDivider(ctx))
            }
        }
        section.addView(devicesList)

     header.setOnClickListener {
        val isCurrentlyExpanded = typeExpanded[key] ?: expanded
        val newExpanded = !isCurrentlyExpanded
        typeExpanded[key] = newExpanded
        devicesList.visibility = if (newExpanded) View.VISIBLE else View.GONE
        chevron.rotation = if (newExpanded) 180f else 0f
        prefs.edit().putBoolean("type_exp_$key", newExpanded).apply()
    }

        return section
    }

    private fun buildDeviceRow(hash: String, dev: JSONObject, type: String): View {
        val ctx = requireContext()
        val state = DisplayStateManager.getValues(hash)
        val cfg = configJson?.optJSONObject("mpg")?.optJSONObject(hash) ?: dev

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            minimumHeight = dpToPx(48)
            isClickable = true
            isFocusable = true
            tag = hash
        }

        val leftBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvName = TextView(ctx).apply {
            text = dev.optString("n", hash)
            textSize = 14f
            setTextColor(ctx.getColor(R.color.gray_200))
        }
        leftBlock.addView(tvName)

        val tvSub = TextView(ctx).apply {
            text = buildSubText(type, state, cfg)
            textSize = 11f
            setTextColor(ctx.getColor(R.color.gray_500))
        }
        leftBlock.addView(tvSub)
        row.addView(leftBlock)

        val rightControl = when (type) {
            "L", "SW", "C", "WH", "F", "H", "LK" -> {
                Switch(ctx).apply {
                    isChecked = state["s"] == 1L
                    setOnCheckedChangeListener { _, checked ->
                        val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                        val old = DisplayStateManager.getValues(hash)
                        main.sendPacket(
                            OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0),
                            oldValue = old,
                            newValue = mapOf("s" to if (checked) 1L else 0L)
                        )
                    }
                }
            }
            "S", "SI" -> {
                val tvVal = TextView(ctx).apply {
                    text = buildRightText(type, state, cfg)
                    textSize = 13f
                    val hasValue = state["v"] != null
                    setTextColor(ctx.getColor(if (hasValue) R.color.green_text else R.color.gray_500))
                }
                tvVal
            }
            "BS" -> {
                val isActive = state["s"] == 1L
                val tvBadge = TextView(ctx).apply {
                    text = if (isActive) "⚠ Тревога" else "✓ Норма"
                    textSize = 12f
                    setTextColor(ctx.getColor(if (isActive) R.color.red_text else R.color.gray_500))
                    setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setCornerRadius(dpToPx(8).toFloat())
                        setColor(ctx.getColor(if (isActive) R.color.red_soft else R.color.gray_800))
                        setStroke(1, ctx.getColor(if (isActive) R.color.red_border else R.color.gray_700))
                    }
                }
                tvBadge
            }
            "A" -> {
                val mode = state["s"] as? Long
                val tvBadge = TextView(ctx).apply {
                    text = when (mode) {
                        1L -> "armed"
                        2L -> "stay"
                        3L -> "night"
                        else -> "disarmed"
                    }
                    textSize = 12f
                    setTextColor(ctx.getColor(if (mode != null && mode != 0L) R.color.yellow_text else R.color.gray_500))
                    setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setCornerRadius(dpToPx(8).toFloat())
                        setColor(ctx.getColor(if (mode != null && mode != 0L) R.color.yellow_soft else R.color.gray_800))
                        setStroke(1, ctx.getColor(if (mode != null && mode != 0L) R.color.yellow_border else R.color.gray_700))
                    }
                }
                tvBadge
            }
            "CV" -> {
                val pos = (state["pos"] as? Number)?.toInt() ?: 0
                val tvVal = TextView(ctx).apply {
                    text = if (pos > 0) "открыты·$pos%" else "закрыты"
                    textSize = 13f
                    setTextColor(ctx.getColor(R.color.green_text))
                }
                tvVal
            }
            "B" -> {
                val btn = Button(ctx).apply {
                    text = "▶"
                    textSize = 16f
                    setOnClickListener {
                        val main = activity as? MainActivity ?: return@setOnClickListener
                        main.sendPacket(OutPacket(tp = PacketType.CMD, id = hash, cmd = "press"))
                    }
                }
                btn
            }
            else -> {
                val tvVal = TextView(ctx).apply {
                    text = state["s"]?.toString() ?: "—"
                    textSize = 13f
                    setTextColor(ctx.getColor(R.color.green_text))
                }
                tvVal
            }
        }
        row.addView(rightControl)

        // Long press → popup
        row.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    longPressRunnable = Runnable {
                        DevicePopupDialog.newInstance(hash, { packet, old, changes ->
                            (activity as? MainActivity)?.sendPacket(packet, oldValue = old, newValue = changes)
                        }, this@ControlFragment)
                            .show(parentFragmentManager, "popup_$hash")
                    }
                    handler.postDelayed(longPressRunnable!!, 500)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable ?: return@setOnTouchListener false)
                }
            }
            false
        }

        return row
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            DisplayStateManager.states.collect { states ->
                if (!isAdded) return@collect
                requireActivity().runOnUiThread {
                    states.keys.forEach { hash: String -> refreshRow(hash) }
                }
            }
        }
    }

    fun onPong(cfgh: String?) {
        // PONG получен
    }

    private fun refreshRow(hash: String) {
        val row = zonesContainer?.findViewWithTag<View>(hash) as? LinearLayout ?: return
        val state = DisplayStateManager.getValues(hash)
        val cfg = configJson?.optJSONObject("mpg")?.optJSONObject(hash) ?: return
        val type = cfg.optString("t", "")

        val enabled = DisplayStateManager.isEnabled(hash)
        row.alpha = if (enabled) 1f else 0.5f
        for (i in 0 until row.childCount) row.getChildAt(i)?.isEnabled = enabled

        val nameBlock = row.getChildAt(0) as? LinearLayout
        (nameBlock?.getChildAt(1) as? TextView)?.text = buildSubText(type, state, cfg)

        when (val ctrl = row.getChildAt(1)) {
            is Switch -> {
                ctrl.setOnCheckedChangeListener(null)
                ctrl.isChecked = state["s"] == 1L
                ctrl.setOnCheckedChangeListener { _, checked ->
                    val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                    val old = DisplayStateManager.getValues(hash)
                    main.sendPacket(
                        OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0),
                        oldValue = old,
                        newValue = mapOf("s" to if (checked) 1L else 0L)
                    )
                }
            }
            is TextView -> ctrl.text = buildRightText(type, state, cfg)
        }

        val zoneId = configJson?.optJSONObject("mpg")
            ?.optJSONObject(hash)?.optString("a") ?: "ustroistva"
        refreshZonePills(zoneId)
    }

    fun buildSubText(type: String, state: Map<String, Any?>, cfg: JSONObject): String = when (type) {
        "L" -> if (state["s"] == 1L) "${state["bri"] ?: ""}% · ${state["ct"] ?: ""}K" else "выкл"
        "C", "WH" -> {
            val th = state["th"]
            val tc = state["tc"]
            if (th != null && tc != null) "цель ${th}°C · сейчас ${tc}°C"
            else if (tc != null) "сейчас ${tc}°C" else "—"
        }
        "S", "SI" -> "${state["v"] ?: "—"}${cfg.optString("u", "")}"
        "CV" -> "${state["pos"] ?: 0}%"
        "BS" -> if (state["s"] == 1L) "Тревога!" else "Норма"
        "SW", "H", "F" -> if (state["s"] == 1L) "вкл" else "выкл"
        else -> ""
    }

    fun buildRightText(type: String, state: Map<String, Any?>, cfg: JSONObject): String = when (type) {
        "S", "SI" -> "${state["v"] ?: "—"}${cfg.optString("u", "")}"
        "BS" -> if (state["s"] == 1L) "⚠ Тревога" else "✓ Норма"
        "A" -> when (state["s"]) {
            1L -> "armed"
            2L -> "stay"
            3L -> "night"
            else -> "disarmed"
        }
        "CV" -> {
            val p = (state["pos"] as? Number)?.toInt() ?: 0
            if (p > 0) "открыты·$p%" else "закрыты"
        }
        else -> state["s"]?.toString() ?: "—"
    }

    private fun getTypeName(type: String): String {
        return when (type) {
            "L" -> "Свет"
            "SW" -> "Выключатели"
            "C" -> "Климат"
            "WH" -> "Бойлер"
            "F" -> "Вентиляция"
            "CV" -> "Жалюзи"
            "LK" -> "Замки"
            "BS" -> "Датчики безопасности"
            "S" -> "Датчики"
            "SI" -> "Сирена"
            "A" -> "Охрана"
            "H" -> "Реле"
            "B" -> "Кнопки"
            else -> "Устройства"
        }
    }

    private fun resolveTypeIcon(type: String): Int {
        return when (type) {
            "L" -> R.drawable.ic_type_bulb
            "SW", "H" -> R.drawable.ic_type_toggle
            "C" -> R.drawable.ic_type_ac
            "WH" -> R.drawable.ic_type_water
            "F" -> R.drawable.ic_type_fan
            "CV" -> R.drawable.ic_type_blinds
            "LK" -> R.drawable.ic_type_lock
            "BS" -> R.drawable.ic_type_sensor
            "S" -> R.drawable.ic_type_thermostat
            "SI" -> R.drawable.ic_type_info
            "A" -> R.drawable.ic_type_security
            "B" -> R.drawable.ic_type_button
            else -> R.drawable.ic_type_info
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(longPressRunnable ?: return)
    }

    private fun createDivider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.gray_700))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun getColor(id: Int) = requireContext().getColor(id)

    private fun getDevicesForZone(
        zoneId: String,
        config: JSONObject,
        unassignedDevices: List<Pair<String, JSONObject>> = emptyList()
    ): List<Pair<String, JSONObject>> {
        val mpg = config.optJSONObject("mpg") ?: return emptyList()
        val devices = mutableListOf<Pair<String, JSONObject>>()
        mpg.keys().forEach { hash ->
            val dev = mpg.optJSONObject(hash) ?: return@forEach
            val devArea = dev.optString("a", "")
            val noArea = devArea == "" || devArea == "null" || dev.opt("a") == null
            if (zoneId == "ustroistva") {
                if (noArea) devices.add(hash to dev)
            } else {
                if (devArea == zoneId) devices.add(hash to dev)
            }
        }
        return devices
    }

    private fun resolveZoneIcon(zoneIcon: String): Int {
        return when (zoneIcon) {
            "home" -> R.drawable.ic_zone_home
            "sofa" -> R.drawable.ic_zone_sofa
            "bed" -> R.drawable.ic_zone_bed
            "kitchen" -> R.drawable.ic_zone_kitchen
            "bathroom" -> R.drawable.ic_zone_bathroom
"devices" -> R.drawable.ic_zone_devices
            "tool" -> R.drawable.ic_zone_tool
            else -> R.drawable.ic_zone_home
        }
    }

    private fun buildZonePills(zoneId: String): List<PillData> {
        val config = configJson ?: return emptyList()
        val mpg = config.optJSONObject("mpg") ?: return emptyList()
        val pills = mutableListOf<PillData>()

        val zoneDevices = mpg.keys().asSequence()
            .filter { hash ->
                val dev = mpg.optJSONObject(hash) ?: return@filter false
                val area = dev.optString("a", "null")
                if (zoneId == "ustroistva") area == "null" || area.isBlank()
                else area == zoneId
            }
            .map { hash -> hash to (mpg.optJSONObject(hash) ?: return@map null) }
            .filterNotNull()
            .toList()

        val tempSensors = zoneDevices.filter { (_, dev) ->
            dev.optString("t") == "S" && dev.optString("u") == "°C"
        }
        if (tempSensors.isNotEmpty()) {
            val values = tempSensors.mapNotNull { (hash, _) ->
                (DisplayStateManager.getValues(hash)["v"] as? Number)?.toDouble()
            }
            if (values.isNotEmpty()) {
                val text = if (values.size == 1)
                    "${values[0]}°"
                else
                    "${values.min()}°..${values.max()}°"
                pills.add(PillData(text, PillType.OK, "thermometer"))
            }
        }

        val humSensors = zoneDevices.filter { (_, dev) ->
            dev.optString("t") == "S" && dev.optString("u") == "%"
        }
        if (humSensors.isNotEmpty()) {
            val values = humSensors.mapNotNull { (hash, _) ->
                (DisplayStateManager.getValues(hash)["v"] as? Number)?.toDouble()
            }
            if (values.isNotEmpty()) {
                pills.add(PillData("${values.first()}%", PillType.OK, "humidity"))
            }
        }

        val lights = zoneDevices.filter { (_, dev) -> dev.optString("t") == "L" }
        if (lights.isNotEmpty()) {
            val onLights = lights.filter { (hash, _) ->
                DisplayStateManager.getValues(hash)["s"] == 1L
            }
            if (onLights.isNotEmpty()) {
                val briValues = onLights.mapNotNull { (hash, _) ->
                    (DisplayStateManager.getValues(hash)["bri"] as? Number)?.toInt()
                }
                val text = if (briValues.isNotEmpty())
                    "${onLights.size} вкл · ${briValues.average().toInt()}%"
                else
                    "${onLights.size} вкл"
                pills.add(PillData(text, PillType.WARN, "bulb"))
            }
        }

        val alarms = zoneDevices.filter { (_, dev) -> dev.optString("t") == "BS" }
            .filter { (hash, _) -> DisplayStateManager.getValues(hash)["s"] == 1L }
        alarms.forEach { (_, dev) ->
            pills.add(PillData("⚠ ${dev.optString("n", "")}", PillType.ERR, "alert"))
        }

        return pills
    }

    private fun renderPills(container: LinearLayout, pills: List<PillData>) {
        container.removeAllViews()
        pills.forEach { pill ->
            val tv = TextView(requireContext()).apply {
                text = pill.text
                textSize = 11f
                setPadding(dpToPx(7), dpToPx(2), dpToPx(7), dpToPx(2))
                val (bgColor, textColor, borderColor) = when (pill.type) {
                    PillType.OK      -> Triple(R.color.green_soft, R.color.green_text, R.color.green_border)
                    PillType.WARN    -> Triple(R.color.yellow_soft, R.color.yellow_text, R.color.yellow_border)
                    PillType.ERR     -> Triple(R.color.red_soft, R.color.red_text, R.color.red_border)
                    PillType.BLUE    -> Triple(R.color.blue_soft, R.color.blue_text, R.color.blue_border)
                    PillType.NEUTRAL -> Triple(R.color.gray_800, R.color.gray_500, R.color.gray_700)
                }
                setTextColor(getColor(textColor))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(99).toFloat()
                    setColor(getColor(bgColor))
                    setStroke(dpToPx(1), getColor(borderColor))
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(4) }
            container.addView(tv, lp)
        }
    }

    private fun refreshZonePills(zoneId: String) {
        val container = zonesContainer
            ?.findViewWithTag<LinearLayout>("zone_summary_$zoneId") ?: return
        val pills = buildZonePills(zoneId)
        requireActivity().runOnUiThread { renderPills(container, pills) }
    }
}
