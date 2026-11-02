package faisalnh.tiviman

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

object QuickSettings {
    data class Row(val key: String, val label: String, val value: String,
        val enabled: Boolean = true, val icon: String = key)

    @Suppress("UNUSED_PARAMETER") // PiP belongs in the toolbar, not this playback-scoped list.
    fun standardRows(video: String, audio: String, captions: String, display: String,
        sleep: String, offsetMs: Int, pipAvailable: Boolean): List<Row> = listOf(
        Row("video", "Video tracks", video),
        Row("audio", "Audio tracks", audio),
        Row("captions", "Closed captions", captions),
        Row("display", "Display mode", display),
        Row("sleep", "Sleep timer", "This session · $sleep"),
        Row("offset", "Audio offset", "Experimental · $offsetMs ms"),
        Row("options", "Channel options", "Favorites, reset choices")
    )

    fun create(activity: Activity, title: String, subtitle: String, rows: List<Row>,
        selected: Int, onRow: (Int) -> Unit, onAllSettings: () -> Unit,
        onDismiss: () -> Unit, toolbarTop: Int? = null): AlertDialog {
        fun dp(value: Int) = GlassMaterial.dp(activity, value.toFloat())
        val items = rows.toList() + Row("settings", "All settings", "Playlists, EPG, appearance")
        val adapter = DrawerAdapter(activity, items)
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            addView(label(activity, title, 28, GlassTokens.textPrimary))
            addView(label(activity, subtitle, 14, GlassTokens.textSecondary).apply {
                setPadding(0, dp(4), 0, dp(16))
            })
            addView(View(activity).apply { setBackgroundColor(0x24FFFFFF) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
        val activate: (Int) -> Unit = { index ->
            if (index in items.indices && items[index].enabled) {
                if (index == rows.size) onAllSettings() else onRow(index)
            }
        }
        val dialog = GlassDialogs.builder(activity).setCustomTitle(header)
            .setAdapter(adapter) { _, index -> activate(index) }.create()
        CapturePolicy.secure(dialog.window)
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            GlassDialogs.style(dialog, blurBehind = false)
            (header.getChildAt(1) as TextView).setTextColor(GlassTokens.textSecondary)
            dialog.window?.let { window ->
                CapturePolicy.secure(window)
                window.setBackgroundDrawable(GlassMaterial.panel(activity, GlassVariant.REGULAR, 24f))
                window.setGravity(Gravity.END or Gravity.TOP)
                val metrics = activity.resources.displayMetrics
                val width = (metrics.widthPixels * .3125f).toInt().coerceAtMost(metrics.widthPixels - dp(32))
                val bottom = toolbarTop ?: (metrics.heightPixels - dp(160))
                window.setLayout(width, (bottom - dp(24)).coerceAtLeast(dp(48)))
                window.attributes = window.attributes.apply {
                    x = dp(16); y = dp(16); windowAnimations = 0
                }
                window.setDimAmount(.30f)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    window.setBackgroundBlurRadius(if (!GlassMaterial.reduceTransparency &&
                        activity.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled) dp(28) else 0)
                }
                if (Settings.Global.getFloat(activity.contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) {
                    window.decorView.apply {
                        alpha = 0f; translationX = width.toFloat()
                        animate().alpha(1f).translationX(0f).setDuration(240)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                }
            }
            val list = dialog.listView
            list.setPadding(dp(12), 0, dp(12), dp(12))
            list.clipToPadding = false
            list.selector = ColorDrawable(android.graphics.Color.TRANSPARENT)
            // AlertController's default listener dismisses plain adapter dialogs after each click.
            list.setOnItemClickListener { _, _, index, _ -> activate(index) }
            fun refresh() {
                for (index in 0 until list.childCount) {
                    (list.getChildAt(index) as? DrawerRow)?.highlight(
                        list.hasFocus() && list.firstVisiblePosition + index == list.selectedItemPosition)
                }
            }
            list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
                override fun onNothingSelected(parent: AdapterView<*>?) = refresh()
            }
            list.setOnFocusChangeListener { _, _ -> refresh() }
            // Rebind after the one-time dialog styling, including rows already attached by show().
            adapter.notifyDataSetChanged()
            list.post {
                val initial = selected.coerceIn(items.indices)
                val enabled = (initial until items.size).firstOrNull { items[it].enabled }
                    ?: items.indices.first { items[it].enabled }
                list.requestFocus()
                list.setSelection(enabled)
                list.post { refresh() }
            }
        }
        return dialog
    }

    private fun label(context: Context, value: String, size: Int, color: Int) = TextView(context).apply {
        text = value
        TvTypography.apply(this, size)
        setTextColor(color)
        isFocusable = false
    }

    private class DrawerAdapter(val context: Context, val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = rows.all { it.enabled }
        override fun isEnabled(position: Int) = rows[position].enabled
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? DrawerRow) ?: DrawerRow(context)).apply {
                bind(rows[position], position == rows.lastIndex)
                val list = parent as? android.widget.ListView
                highlight(list?.hasFocus() == true && list.selectedItemPosition == position)
            }
    }

    private class DrawerRow(context: Context) : LinearLayout(context) {
        private fun dp(value: Int) = GlassMaterial.dp(context, value.toFloat())
        private val body = LinearLayout(context)
        private val glyph = SettingGlyph(context)
        private val title = label(context, "", 18, GlassTokens.textPrimary)
        private val value = label(context, "", 14, GlassTokens.textSecondary)
        private val chevron = SettingGlyph(context).apply { key = "chevron" }
        private var available = true

        init {
            orientation = VERTICAL
            isFocusable = false
            layoutParams = AbsListView.LayoutParams(-1, -2)
            body.gravity = Gravity.CENTER_VERTICAL
            body.setPadding(dp(12), dp(10), dp(12), dp(10))
            body.minimumHeight = dp(64)
            addView(body, LayoutParams(-1, -2))
            body.addView(glyph, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            body.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title)
                addView(value.apply { setPadding(0, dp(2), 0, 0) })
            }, LayoutParams(0, -2, 1f))
            body.addView(chevron, LayoutParams(dp(16), dp(24)))
        }

        fun bind(row: Row, last: Boolean) {
            available = row.enabled
            isEnabled = available
            setPadding(0, if (last) dp(8) else 0, 0, dp(2))
            title.text = row.label; value.text = row.value; glyph.key = row.icon
            chevron.visibility = if (last) VISIBLE else GONE
            contentDescription = "${row.label}, ${row.value}"
            highlight(false)
        }

        fun highlight(selected: Boolean) {
            val lit = available && selected
            body.background = if (lit) GlassMaterial.lens(context) else GlassMaterial.focusable(context)
            title.setTextColor(if (lit) GlassTokens.lensText else GlassTokens.textPrimary)
            value.setTextColor(if (lit) GlassTokens.lensText else GlassTokens.textSecondary)
            glyph.ink = if (lit) GlassTokens.lensText else GlassTokens.textPrimary
            chevron.ink = glyph.ink
        }
    }

    private class SettingGlyph(context: Context) : View(context) {
        private val audioOffset = PlayerIcon(context, "Audio offset")
        var key: String = "settings"
            set(value) { field = value; invalidate() }
        var ink: Int = GlassTokens.textPrimary
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (key == "offset") {
                audioOffset.ink = ink
                audioOffset.layout(0, 0, width, height)
                audioOffset.draw(canvas)
                return
            }
            val save = canvas.save()
            canvas.scale(width / 24f, height / 24f)
            paint.color = ink
            fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)
            when (key) {
                "video", "display", "captions" -> {
                    canvas.drawRoundRect(3f, 5f, 21f, 18f, 2f, 2f, paint)
                    when (key) {
                        "captions" -> { line(6f, 10f, 10f, 10f); line(14f, 10f, 18f, 10f)
                            line(6f, 14f, 10f, 14f); line(14f, 14f, 18f, 14f) }
                        "display" -> { line(8f, 21f, 16f, 21f); line(12f, 18f, 12f, 21f) }
                        else -> canvas.drawPath(Path().apply {
                            moveTo(10f, 8f); lineTo(16f, 11.5f); lineTo(10f, 15f); close()
                        }, paint)
                    }
                }
                "audio" -> {
                    canvas.drawPath(Path().apply {
                        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f)
                        lineTo(8f, 15f); lineTo(4f, 15f); close()
                    }, paint)
                    canvas.drawArc(11f, 4f, 22f, 20f, -60f, 120f, false, paint)
                }
                "sleep" -> {
                    canvas.drawCircle(12f, 12f, 8f, paint)
                    line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
                }
                "options" -> {
                    for (i in 0..2) {
                        val y = 6f + i * 6
                        val x = if (i == 1) 15f else 8f
                        line(3f, y, x - 2, y); line(x + 2, y, 21f, y)
                        canvas.drawCircle(x, y, 2f, paint)
                    }
                }
                "chevron" -> { line(9f, 7f, 14f, 12f); line(14f, 12f, 9f, 17f) }
                else -> {
                    canvas.drawCircle(12f, 12f, 6f, paint)
                    canvas.drawCircle(12f, 12f, 2.5f, paint)
                    for (i in 0..7) {
                        canvas.save(); canvas.rotate(i * 45f, 12f, 12f)
                        line(12f, 3f, 12f, 6f); canvas.restore()
                    }
                }
            }
            canvas.restoreToCount(save)
        }
    }
}
