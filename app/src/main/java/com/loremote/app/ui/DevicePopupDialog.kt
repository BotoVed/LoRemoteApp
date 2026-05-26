package com.loremote.app.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.loremote.app.R
import com.loremote.app.protocol.OutPacket
import com.loremote.app.protocol.PacketType
import org.json.JSONObject

class DevicePopupDialog(
    private val hash: String,
    private val dev: JSONObject,
    private val type: String,
    private val state: Map<String, Any?>?,
    private val onSend: (OutPacket) -> Unit
) : BottomSheetDialogFragment() {

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
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }

        val bri = (state?.get("bri") as? Long)?.toInt() ?: 50
        addSlider(layout, "Яркость", "%", 1, 100, bri) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, bri = v))
        }

        val ct = (state?.get("ct") as? Long)?.toInt() ?: 4000
        addSlider(layout, "Температура цвета", "K", 2700, 6500, ct, 100) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, ct = v))
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
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, bri = bri, ct = ct))
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
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }

        val tc = (state?.get("tc") as? Number)?.toInt()
        if (tc != null) {
            val tvCurr = TextView(ctx).apply {
                text = "Текущая: $tc°C"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.gray_300))
                setPadding(0, 0, 0, dpToPx(12))
            }
            layout.addView(tvCurr)
        }

        val th = (state?.get("th") as? Number)?.toInt() ?: 22
        addTempControl(layout, "Целевая температура", "°C", 16, 30, th) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()))
        }

        val modes = listOf("cool" to "❄", "heat" to "🔥", "fan" to "💨", "auto" to "🔄", "dry" to "💧")
        val currentMode = (state?.get("mode") as? String) ?: "cool"
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
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, md = m))
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
        val currentFan = (state?.get("fan") as? String) ?: "auto"
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
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, fn = f))
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
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }

        val tc = (state?.get("tc") as? Number)?.toInt()
        if (tc != null) {
            val tvCurr = TextView(ctx).apply {
                text = "Текущая: $tc°C"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.gray_300))
                setPadding(0, 0, 0, dpToPx(12))
            }
            layout.addView(tvCurr)
        }

        val th = (state?.get("th") as? Number)?.toInt() ?: 55
        addTempControl(layout, "Целевая температура", "°C", 35, 75, th) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()))
        }

        val modes = listOf("eco" to "Эко", "comfort" to "Комфорт", "power" to "Мощный", "vacation" to "Отпуск")
        val currentMode = (state?.get("mode") as? String) ?: "comfort"
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
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, md = m))
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
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }

        val speed = (state?.get("speed") as? Long)?.toInt() ?: 50
        addSlider(layout, "Скорость", "%", 1, 100, speed) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, sp = v))
        }
    }

    private fun buildHumidifierControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }

        val tc = (state?.get("tc") as? Number)?.toInt()
        if (tc != null) {
            val tvCurr = TextView(ctx).apply {
                text = "Текущая: $tc%"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.gray_300))
                setPadding(0, 0, 0, dpToPx(12))
            }
            layout.addView(tvCurr)
        }

        val th = (state?.get("th") as? Number)?.toInt() ?: 50
        addTempControl(layout, "Целевая влажность", "%", 20, 80, th) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()))
        }
    }

    private fun buildCoverControls(layout: LinearLayout) {
        val ctx = requireContext()
        val pos = (state?.get("pos") as? Long)?.toInt() ?: 0
        addSlider(layout, "Позиция", "%", 0, 100, pos) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, pos = v))
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }

        val openBtn = Button(ctx).apply {
            text = "Открыть"
            setTextColor(ctx.getColor(R.color.green_text))
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "open")) }
        }
        openBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        openBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(openBtn)

        val stopBtn = Button(ctx).apply {
            text = "Стоп"
            setTextColor(ctx.getColor(R.color.gray_400))
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "stop")) }
        }
        stopBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stopBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(stopBtn)

        val closeBtn = Button(ctx).apply {
            text = "Закрыть"
            setTextColor(ctx.getColor(R.color.red_text))
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "close")) }
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
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "unlock")) }
        }
        unlockBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        unlockBtn.layoutParams.height = dpToPx(44)
        btnRow.addView(unlockBtn)

        val lockBtn = Button(ctx).apply {
            text = "Закрыть замок"
            textSize = 14f
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "lock")) }
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
            setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = "press")) }
        }
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)
        )
        layout.addView(btn)
    }

    private fun buildBinarySensorControl(layout: LinearLayout) {
        val ctx = requireContext()
        val active = state?.get("s") == 1L

        val badge = TextView(ctx).apply {
            text = if (active) "⚠️ ТРЕВОГА" else "✓ Норма"
            textSize = 18f
            setTextColor(ctx.getColor(if (active) R.color.red_text else R.color.green_text))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(badge)

        val ts = state?.get("ts") as? String
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
        val v = state?.get("v")
        val u = dev.optString("u", "")
        val ts = state?.get("ts") as? String

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
        val active = state?.get("s") == 1L

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

        val on = state?.get("s") == 1L
        addToggleRow(layout, "Сирена", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }
    }

    private fun buildAlarmControl(layout: LinearLayout) {
        val ctx = requireContext()
        val mode = (state?.get("s") as? Long)

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
                onSend(OutPacket(tp = PacketType.CMD, id = hash, s = 1L))
            }
        }
        awayBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        awayBtn.layoutParams.height = dpToPx(40)
        armRow.addView(awayBtn)

        val stayBtn = Button(ctx).apply {
            text = "Дома"
            textSize = 13f
            setOnClickListener {
                onSend(OutPacket(tp = PacketType.CMD, id = hash, s = 2L))
            }
        }
        stayBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stayBtn.layoutParams.height = dpToPx(40)
        armRow.addView(stayBtn)

        val nightBtn = Button(ctx).apply {
            text = "Ночь"
            textSize = 13f
            setOnClickListener {
                onSend(OutPacket(tp = PacketType.CMD, id = hash, s = 3L))
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
                    onSend(OutPacket(tp = PacketType.CMD, id = hash, pin = pin, s = 0L))
                }
            }
        }
        disarmBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)
        )
        layout.addView(disarmBtn)
    }

    private fun buildToggleControl(layout: LinearLayout) {
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }
    }

    private fun buildGenericControl(layout: LinearLayout) {
        val ctx = requireContext()
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
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
        addSlider(layout, label, unit, min, max, value, 1, onChange)
    }

    private fun addSlider(layout: LinearLayout, label: String, unit: String, min: Int, max: Int, value: Int, step: Int, onChange: (Int) -> Unit) {
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
            progress = value - min
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                    val v = p + min
                    tvVal.text = "$v$unit"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { onChange(sb.progress + min) }
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
}
