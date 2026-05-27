package com.loremote.app.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.loremote.app.R
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
import com.loremote.app.state.DeviceStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DevicePopupDialog(
    private val hash: String,
    private val dev: JSONObject,
    private val type: String,
    private val onSend: (OutPacket, Map<String, Any?>, Map<String, Any?>) -> Unit
) : BottomSheetDialogFragment() {

   private val debounceJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private var currentVisibleState: Map<String, Any?> = emptyMap()

    private fun updateControls(newState: Map<String, Any?>) {
        val scroll = view?.parent as? ScrollView ?: return
        val layout = scroll.getChildAt(0) as? LinearLayout ?: return
        for (i in 1 until layout.childCount) {
            val section = layout.getChildAt(i)
            if (section is LinearLayout) {
                updateControlSection(section, newState)
            }
        }
    }

    private fun updateControlSection(section: LinearLayout, newState: Map<String, Any?>) {
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            if (child is Switch) {
                val parent = child.parent
                if (parent is LinearLayout) {
                    val label = parent.getChildAt(0) as? TextView
                    val field = label?.text?.let { findFieldFromLabel(it.toString()) } ?: continue
                    child.setOnCheckedChangeListener(null)
                    child.isChecked = newState[field] == 1L
                    child.setOnCheckedChangeListener { _, checked ->
                        val main = activity as? MainActivity ?: return@setOnCheckedChangeListener
                        val changes = mapOf(field to if (checked) 1L else 0L)
                        main.sendPacket(
                            OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0),
                            newValue = changes
                        )
                    }
                }
            }
            if (child is TextView) {
                val parent = child.parent
                if (parent is LinearLayout) {
                    val label = parent.getChildAt(0)
                    if (label is TextView) {
                        val fieldName = label.text?.let { findFieldFromLabel(it.toString()) }
                        if (fieldName != null && child.id != View.NO_ID) {
                            // This is a value TextView for a slider
                            val value = newState[fieldName]
                            if (value != null) {
                                val unit = (child.parent as? LinearLayout)?.getChildAt(1) as? TextView
                                if (unit != null) {
                                    val parent2 = unit.parent as? LinearLayout
                                    if (parent2 != null && parent2.childCount > 0) {
                                        val seekBar = parent2.getChildAt(0) as? SeekBar
                                        if (seekBar != null) {
                                            unit.text = "$value"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (child is SeekBar) {
                val fieldName = child.tag as? String ?: continue
                val value = newState[fieldName]
                if (value != null) {
                    val v = (value as? Number)?.toInt() ?: continue
                    child.progress = v - child.min
                    val parent = child.parent as? LinearLayout ?: continue
                    val tvVal = parent?.getChildAt(1) as? TextView
                    tvVal?.text = "$v"
                }
            }
        }
    }

    private fun findFieldFromLabel(label: String): String? {
        return when (label) {
            "Включить", "Сирена" -> "s"
            "Яркость" -> "bri"
            "Температура цвета" -> "ct"
            "Скорость" -> "sp"
            "Позиция" -> "pos"
            else -> null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentVisibleState = DeviceStateManager.visible(hash)
        lifecycleScope.launch {
            DeviceStateManager.states.collect { states ->
                val newState = DeviceStateManager.visible(hash)
                if (newState != currentVisibleState) {
                    currentVisibleState = newState
                    updateControls(newState)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(24))
        }

        val handle = View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.gray_700))
            val lp = LinearLayout.LayoutParams(dpToPx(36), dpToPx(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(16)
            }
            layoutParams = lp
        }
        layout.addView(handle)

        val tvTitle = TextView(ctx).apply {
            text = dev.optString("n", hash)
            textSize = 17f
            setTextColor(ctx.getColor(R.color.gray_100))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvTitle)

        val tvType = TextView(ctx).apply {
            text = type
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(tvType)

        when (type) {
            "L" -> buildLightControls(layout)
            "SW" -> buildToggleControl(layout)
            "C" -> buildClimateControls(layout)
            "WH" -> buildBoilerControls(layout)
            "F" -> buildFanControls(layout)
            "H" -> buildHumidifierControls(layout)
            "CV" -> buildCoverControls(layout)
            "LK" -> buildLockControls(layout)
            "BS" -> buildBinarySensorControl(layout)
            "SI" -> buildSirenControl(layout)
            "A" -> buildAlarmControl(layout)
            "S" -> buildSensorControl(layout)
            "B" -> buildButtonControl(layout)
            else -> buildGenericControl(layout)
        }

        scroll.addView(layout)
        return scroll
    }

private fun buildLightControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }

        val bri = toLong(currentVisibleState["bri"])?.toInt() ?: 50
        addSlider(layout, "Яркость", "%", 1, 100, bri, 1, "bri") { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, bri = v), DeviceStateManager.visible(hash), mapOf("bri" to v.toLong()))
        }

        val ct = toLong(currentVisibleState["ct"])?.toInt() ?: 4000
        addSlider(layout, "Температура цвета", "K", 2700, 6500, ct, 100, "ct") { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, ct = v), DeviceStateManager.visible(hash), mapOf("ct" to v.toLong()))
        }

        addPresetsRow(layout)
    }

    private fun addPresetsRow(layout: LinearLayout) {
        val ctx = requireContext()
        val label = TextView(ctx).apply {
            text = "Пресеты"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            setPadding(0, 0, 0, dpToPx(8))
        }
        layout.addView(label)

        val presetsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val presets = listOf(
            Triple("Чтение", 100, 6500),
            Triple("Дневной", 80, 4000),
            Triple("Вечерний", 50, 3000),
            Triple("Ночник", 10, 2700)
        )

        presets.forEach { (name, bri, ct) ->
            val btn = Button(ctx).apply {
                text = name
                textSize = 11f
                setOnClickListener {
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, bri = bri, ct = ct), DeviceStateManager.visible(hash), emptyMap())
                }
            }
            btn.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                if (name != "Ночник") rightMargin = dpToPx(4)
            }
            presetsRow.addView(btn)
        }
        layout.addView(presetsRow)
    }

private fun buildClimateControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }

        val tc = (currentVisibleState["tc"] as? Number)?.toInt()
        if (tc != null) {
            val tvCurr = TextView(ctx).apply {
                text = "Текущая: $tc°C"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.gray_300))
                setPadding(0, 0, 0, dpToPx(12))
            }
            layout.addView(tvCurr)
        }

        val th = (currentVisibleState["th"] as? Number)?.toInt() ?: 22
        addTempControl(layout, "Целевая температура", "°C", 16, 30, th) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()), DeviceStateManager.visible(hash), mapOf("th" to v.toDouble()))
        }

        val modes = listOf("cool" to "❄", "heat" to "🔥", "fan" to "💨", "auto" to "🔄", "dry" to "💧")
        val currentMode = (currentVisibleState["mode"] as? String) ?: "cool"
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        val modeLabel = TextView(ctx).apply {
            text = "Режим"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeRow.addView(modeLabel)
        modes.forEach { (m, icon) ->
            val btn = Button(ctx).apply {
                text = icon
                textSize = 14f
                setOnClickListener {
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, md = m), DeviceStateManager.visible(hash), mapOf("md" to m))
                }
                if (m == currentMode) {
                    setTextColor(ctx.getColor(R.color.green_text))
                }
            }
            btn.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            btn.layoutParams.height = dpToPx(40)
            modeRow.addView(btn)
        }
        layout.addView(modeRow)

val fans = listOf("low" to "Низкая", "med" to "Средняя", "high" to "Высокая", "auto" to "Авто")
        val currentFan = (currentVisibleState["fan"] as? String) ?: "auto"
        val fanRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        val fanLabel = TextView(ctx).apply {
            text = "Вентилятор"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fanRow.addView(fanLabel)
        fans.forEach { (f, name) ->
            val btn = Button(ctx).apply {
                text = name
                textSize = 11f
                setOnClickListener {
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, fn = f), DeviceStateManager.visible(hash), mapOf("fn" to f))
                }
                if (f == currentFan) {
                    setTextColor(ctx.getColor(R.color.green_text))
                }
            }
            btn.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            btn.layoutParams.height = dpToPx(40)
            fanRow.addView(btn)
        }
        layout.addView(fanRow)
    }

    private fun buildBoilerControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }

        val tc = (currentVisibleState["tc"] as? Number)?.toInt()
        if (tc != null) {
            val tvCurr = TextView(ctx).apply {
                text = "Текущая: $tc°C"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.gray_300))
                setPadding(0, 0, 0, dpToPx(12))
            }
            layout.addView(tvCurr)
        }

        val th = (currentVisibleState["th"] as? Number)?.toInt() ?: 55
        addTempControl(layout, "Целевая температура", "°C", 35, 75, th) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()), DeviceStateManager.visible(hash), mapOf("th" to v.toDouble()))
        }

        val modes = listOf("eco" to "Эко", "comfort" to "Комфорт", "power" to "Мощный", "vacation" to "Отпуск")
        val currentMode = (currentVisibleState["mode"] as? String) ?: "comfort"
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        val modeLabel = TextView(ctx).apply {
            text = "Режим"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeRow.addView(modeLabel)
        modes.forEach { (m, name) ->
            val btn = Button(ctx).apply {
                text = name
                textSize = 11f
                setOnClickListener {
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, md = m), DeviceStateManager.visible(hash), mapOf("md" to m))
                }
                if (m == currentMode) {
                    setTextColor(ctx.getColor(R.color.green_text))
                }
            }
            btn.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            btn.layoutParams.height = dpToPx(40)
            modeRow.addView(btn)
        }
        layout.addView(modeRow)
    }

    private fun buildFanControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }

        val speed = toLong(currentVisibleState["speed"])?.toInt() ?: 50
        addSlider(layout, "Скорость", "%", 1, 100, speed, 1, "sp") { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, sp = v), DeviceStateManager.visible(hash), mapOf("sp" to v.toLong()))
        }
    }

    private fun buildHumidifierControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }

       val tc = (currentVisibleState["tc"] as? Number)?.toInt()
        if (tc != null) {
            val tvCurr = TextView(ctx).apply {
                text = "Текущая: $tc%"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.gray_300))
                setPadding(0, 0, 0, dpToPx(12))
            }
            layout.addView(tvCurr)
        }

        val th = (currentVisibleState["th"] as? Number)?.toInt() ?: 50
        addTempControl(layout, "Целевая влажность", "%", 20, 80, th) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()), DeviceStateManager.visible(hash), mapOf("th" to v.toDouble()))
        }
    }

   private fun buildCoverControls(layout: LinearLayout) {
        val ctx = requireContext()
        val pos = toLong(currentVisibleState["pos"])?.toInt() ?: 0
        addSlider(layout, "Позиция", "%", 0, 100, pos, 1, "pos") { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, pos = v), DeviceStateManager.visible(hash), mapOf("pos" to v.toLong()))
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }

        val openBtn = Button(ctx).apply {
            text = "Открыть"
            setTextColor(ctx.getColor(R.color.green_text))
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "open"), DeviceStateManager.visible(hash), emptyMap()) }
        }
        openBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        openBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(openBtn)

        val stopBtn = Button(ctx).apply {
            text = "Стоп"
            setTextColor(ctx.getColor(R.color.gray_400))
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "stop"), DeviceStateManager.visible(hash), emptyMap()) }
        }
        stopBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stopBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(stopBtn)

        val closeBtn = Button(ctx).apply {
            text = "Закрыть"
            setTextColor(ctx.getColor(R.color.red_text))
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "close"), DeviceStateManager.visible(hash), emptyMap()) }
        }
        closeBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        closeBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(closeBtn)

        layout.addView(btnRow)
    }

    private fun buildLockControls(layout: LinearLayout) {
        val ctx = requireContext()
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        val unlockBtn = Button(ctx).apply {
            text = "Открыть замок"
            textSize = 14f
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "unlock"), DeviceStateManager.visible(hash), emptyMap()) }
        }
        unlockBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        unlockBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(unlockBtn)

        val lockBtn = Button(ctx).apply {
            text = "Закрыть замок"
            textSize = 14f
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "lock"), DeviceStateManager.visible(hash), emptyMap()) }
        }
        lockBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lockBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(lockBtn)
        layout.addView(btnRow)
    }

    private fun buildButtonControl(layout: LinearLayout) {
        val ctx = requireContext()
        val btn = Button(ctx).apply {
            text = "▶ Нажать"
            textSize = 14f
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "press"), DeviceStateManager.visible(hash), emptyMap()) }
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)
        )
        layout.addView(btn)
    }

    private fun buildBinarySensorControl(layout: LinearLayout) {
        val ctx = requireContext()
        val active = currentVisibleState["s"] == 1L

        val badge = TextView(ctx).apply {
            text = if (active) "⚠️ ТРЕВОГА" else "✓ Норма"
            textSize = 18f
            setTextColor(ctx.getColor(if (active) R.color.red_text else R.color.green_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(badge)

        val ts = currentVisibleState["ts"] as? String
        val tvTime = TextView(ctx).apply {
            text = if (ts != null) "Обновлено: $ts" else "Нет данных"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.gray_500))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(tvTime)
    }

    private fun buildSensorControl(layout: LinearLayout) {
        val ctx = requireContext()
        val v = currentVisibleState["v"]
        val u = dev.optString("u", "")
        val ts = currentVisibleState["ts"] as? String

        val tvVal = TextView(ctx).apply {
            text = if (v != null) "$v" else "—"
            textSize = 48f
            setTextColor(ctx.getColor(R.color.green_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dpToPx(24), 0, dpToPx(8))
        }
        layout.addView(tvVal)

        val tvUnit = TextView(ctx).apply {
            text = u
            textSize = 16f
            setTextColor(ctx.getColor(R.color.gray_400))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(tvUnit)

        val tvTime = TextView(ctx).apply {
            text = if (ts != null) "Обновлено: $ts" else "Нет данных"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.gray_500))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dpToPx(16), 0, dpToPx(12))
        }
        layout.addView(tvTime)
    }

    private fun buildSirenControl(layout: LinearLayout) {
        val ctx = requireContext()
        val active = currentVisibleState["s"] == 1L

        val warning = TextView(ctx).apply {
            text = "⚠️ Внимание: громкий звук"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.yellow_text))
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.badge_yellow)
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
        layout.addView(warning)

        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Сирена", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }
    }

    private fun buildAlarmControl(layout: LinearLayout) {
        val ctx = requireContext()
        val mode = toLong(currentVisibleState["s"])

        val status = TextView(ctx).apply {
            text = when (mode) {
                1L -> "armed"
                2L -> "stay"
                3L -> "night"
                else -> "disarmed"
            }
            textSize = 16f
            setTextColor(ctx.getColor(if (mode != null && mode != 0L) R.color.yellow_text else R.color.gray_400))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(status)

        val armRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        val awayBtn = Button(ctx).apply {
            text = "Уход"
            textSize = 13f
            setOnClickListener {
                val old = DeviceStateManager.visible(hash)
                onSend(OutPacket(tp = PacketType.CMD, id = hash, s = 1L), old, mapOf("s" to 1L))
            }
        }
        awayBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        awayBtn.layoutParams.height = dpToPx(40)
        armRow.addView(awayBtn)

        val stayBtn = Button(ctx).apply {
            text = "Дома"
            textSize = 13f
            setOnClickListener {
                val old = DeviceStateManager.visible(hash)
                onSend(OutPacket(tp = PacketType.CMD, id = hash, s = 2L), old, mapOf("s" to 2L))
            }
        }
        stayBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stayBtn.layoutParams.height = dpToPx(40)
        armRow.addView(stayBtn)

        val nightBtn = Button(ctx).apply {
            text = "Ночь"
            textSize = 13f
            setOnClickListener {
                val old = DeviceStateManager.visible(hash)
                onSend(OutPacket(tp = PacketType.CMD, id = hash, s = 3L), old, mapOf("s" to 3L))
            }
        }
        nightBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        nightBtn.layoutParams.height = dpToPx(40)
        armRow.addView(nightBtn)
        layout.addView(armRow)

        val pinInput = EditText(ctx).apply {
            hint = "PIN"
            textSize = 14f
            setInputType(android.text.InputType.TYPE_CLASS_NUMBER)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        layout.addView(pinInput)

        val disarmBtn = Button(ctx).apply {
            text = "Снять"
            setTextColor(ctx.getColor(R.color.red_text))
            setOnClickListener {
                val pin = pinInput.text.toString()
                if (pin.isNotBlank()) {
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, pin = pin, s = 0L), DeviceStateManager.visible(hash), mapOf("s" to 0L))
                }
            }
        }
        disarmBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)
        )
        layout.addView(disarmBtn)
    }

    private fun buildToggleControl(layout: LinearLayout) {
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }
    }

    private fun buildGenericControl(layout: LinearLayout) {
        val ctx = requireContext()
        val on = currentVisibleState["s"] == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            val old = DeviceStateManager.visible(hash)
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0), old, mapOf("s" to if (checked) 1L else 0L))
        }
    }

    private fun addToggleRow(layout: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        val tv = TextView(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(ctx.getColor(R.color.gray_200))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(tv)
        row.addView(sw)
        layout.addView(row)
    }

    private fun addSlider(layout: LinearLayout, label: String, unit: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        addSlider(layout, label, unit, min, max, value, 1, "", onChange)
    }

    private fun addSlider(layout: LinearLayout, label: String, unit: String, min: Int, max: Int, value: Int, step: Int, fieldName: String, onChange: (Int) -> Unit) {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            setPadding(0, 0, 0, dpToPx(8))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tvVal = TextView(ctx).apply {
            text = "$value$unit"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.gray_200))
            minWidth = dpToPx(54)
            gravity = android.view.Gravity.END
        }
        val seek = SeekBar(ctx).apply {
            this.max = max - min
            tag = fieldName
            progress = value - min
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p + min
                    tvVal.text = "$v$unit"
                    debounceJobs[fieldName]?.cancel()
                    debounceJobs[fieldName] = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        kotlinx.coroutines.delay(500)
                        onChange(v)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val v = sb.progress + min
                    tvVal.text = "$v$unit"
                }
            })
        }
        row.addView(seek)
        row.addView(tvVal)
        layout.addView(tv)
        layout.addView(row)
    }

    private fun addTempControl(layout: LinearLayout, label: String, unit: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val ctx = requireContext()
        var current = value
        val tv = TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(ctx.getColor(R.color.gray_500))
            setPadding(0, 0, 0, dpToPx(8))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(12))
        }
        val tvVal = TextView(ctx).apply {
            text = "$current$unit"
            textSize = 28f
            setTextColor(ctx.getColor(R.color.gray_100))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnMinus = Button(ctx).apply {
            text = "−"
            textSize = 18f
            setOnClickListener {
                if (current > min) {
                    current--
                    tvVal.text = "$current$unit"
                    onChange(current)
                }
            }
        }
        val btnPlus = Button(ctx).apply {
            text = "+"
            textSize = 18f
            setOnClickListener {
                if (current < max) {
                    current++
                    tvVal.text = "$current$unit"
                    onChange(current)
                }
            }
        }
        row.addView(tvVal)
        row.addView(btnMinus)
        row.addView(btnPlus)
        layout.addView(tv)
        layout.addView(row)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun toLong(v: Any?): Long? = when (v) {
        is Number -> (v as? Number)?.toLong() ?: 0L
        else -> null
    }
}
