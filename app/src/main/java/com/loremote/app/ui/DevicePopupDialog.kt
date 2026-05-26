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
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(24))
        }

        // Handle
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
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(tvTitle)

        when (type) {
            "L" -> buildLightControls(layout)
            "C" -> buildClimateControls(layout)
            "WH" -> buildBoilerControls(layout)
            "SW", "F", "H" -> buildToggleControl(layout)
            "CV" -> buildCoverControls(layout)
            "LK" -> buildLockControls(layout)
            "BS" -> buildBinarySensorControl(layout)
            else -> buildGenericControl(layout)
        }

        return layout
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
        addSlider(layout, "Температура цвета", "K", 2700, 6500, ct) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, ct = v))
        }
    }

    private fun buildClimateControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }
        addTempControl(layout, "Целевая температура", 16, 30,
            (state?.get("th") as? Double)?.toInt() ?: 22) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()))
        }
    }

    private fun buildBoilerControls(layout: LinearLayout) {
        val ctx = requireContext()
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }
        addTempControl(layout, "Целевая температура", 35, 75,
            (state?.get("th") as? Double)?.toInt() ?: 55) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, th = v.toDouble()))
        }
    }

    private fun buildToggleControl(layout: LinearLayout) {
        val on = state?.get("s") == 1L
        addToggleRow(layout, "Включить", on) { checked ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, s = if (checked) 1 else 0))
        }
    }

    private fun buildCoverControls(layout: LinearLayout) {
        val ctx = requireContext()
        val pos = (state?.get("pos") as? Long)?.toInt() ?: 0
        addSlider(layout, "Позиция", "%", 0, 100, pos) { v ->
            onSend(OutPacket(tp = PacketType.CMD, id = hash, pos = v))
        }
        listOf("open" to "Открыть", "stop" to "Стоп", "close" to "Закрыть").forEach { (cmd, label) ->
            val btn = Button(ctx).apply {
                text = label
                setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = cmd)) }
            }
            layout.addView(btn)
        }
    }

    private fun buildLockControls(layout: LinearLayout) {
        val ctx = requireContext()
        listOf("unlock" to "Открыть замок", "lock" to "Закрыть замок").forEach { (cmd, label) ->
            val btn = Button(ctx).apply {
                text = label
                setOnClickListener { onSend(OutPacket(tp = PacketType.CMD, id = hash, cmd = cmd)) }
            }
            layout.addView(btn)
        }
    }

    private fun buildBinarySensorControl(layout: LinearLayout) {
        val ctx = requireContext()
        val active = state?.get("s") == 1L
        val tv = TextView(ctx).apply {
            text = if (active) "⚠️ Сработал" else "✓ Норма"
            textSize = 15f
            setTextColor(ctx.getColor(if (active) R.color.red_text else R.color.green_text))
            setPadding(0, 0, 0, dpToPx(8))
        }
        layout.addView(tv)
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
                    tvVal.text = "${p + min}$unit"
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

    private fun addTempControl(layout: LinearLayout, label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
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
            text = "$current°C"
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
                    tvVal.text = "$current°C"
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
                    tvVal.text = "$current°C"
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
