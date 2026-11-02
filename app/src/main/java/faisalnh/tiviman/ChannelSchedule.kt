package faisalnh.tiviman

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

internal object ChannelScheduleLogic {
    enum class Position { PAST, CURRENT, FUTURE, UNKNOWN }
    data class Days(val today: List<Programme>, val tomorrow: List<Programme>)

    fun position(programme: Programme, nowMinute: Int): Position = when {
        programme.duration <= 0 -> Position.UNKNOWN
        programme.start.toLong() + programme.duration <= nowMinute.toLong() -> Position.PAST
        programme.start <= nowMinute -> Position.CURRENT
        else -> Position.FUTURE
    }

    fun currentIndex(programmes: List<Programme>, nowMinute: Int): Int =
        programmes.indexOfFirst { hasInformation(it) && position(it, nowMinute) == Position.CURRENT }

    fun hasInformation(programme: Programme): Boolean =
        programme.title.isNotBlank() && !programme.title.trim().equals("No information", ignoreCase = true)

    fun isEmpty(programmes: List<Programme>): Boolean = programmes.none { hasInformation(it) }

    fun durationLabel(programme: Programme): String =
        if (programme.duration > 0) "${programme.duration} min" else "Duration unavailable"

    fun split(programmes: List<Programme>, nowMinute: Int, time: (Int) -> String): Days {
        val rows = programmes.filter { hasInformation(it) }.sortedBy { it.start }
        val midnight = nextMidnight(nowMinute, time) ?: return Days(rows, emptyList())
        // Starts determine the day; a programme spanning midnight stays on its starting day.
        val following = nextMidnight(midnight, time) ?: return Days(rows, emptyList())
        return Days(rows.filter { it.start < midnight }, rows.filter { it.start in midnight until following })
    }

    private fun nextMidnight(nowMinute: Int, time: (Int) -> String): Int? {
        fun clock(value: Int): Int? {
            val match = Regex("^(\\d{2}):(\\d{2})$").matchEntire(time(value)) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
        }
        val nowClock = clock(nowMinute) ?: return null
        // Real EPG uses epoch minutes. Calendar preserves local-midnight and DST boundaries.
        if (nowMinute >= 1_000_000) {
            val calendar = Calendar.getInstance().apply { timeInMillis = nowMinute * 60000L }
            if (calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE) == nowClock) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val candidate = calendar.timeInMillis / 60000L
                if (candidate in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && clock(candidate.toInt()) == 0)
                    return candidate.toInt()
            }
        }
        val candidate = nowMinute.toLong() + 1440 - nowClock
        if (candidate !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return candidate.toInt().takeIf { clock(it) == 0 && clock(it - 1) == 1439 }
    }
}

class ChannelScheduleOverlay(context: Context) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var dismiss: (() -> Unit)? = null
    private var list: ListView? = null
    private var tabs = emptyList<TextView>()
    private var visibleRows = emptyList<Programme>()
    private var day = 0
    private var inset = GlassMaterial.dp(context, 16f)

    init {
        visibility = GONE
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        setBackgroundColor(GlassTokens.scrimOverlay)
        addView(panel)
        setOnClickListener { dismiss?.invoke() }
        panel.isClickable = true
    }

    fun show(channel: Channel, channelNumber: Int, nowMinute: Int, time: (Int) -> String,
        updatedLabel: String?, scale: Float, onDismiss: () -> Unit) {
        animate().cancel()
        panel.animate().cancel()
        dismiss = onDismiss
        // scale is the host's logical-dp scale; typography remains system-scaled sp.
        val unit = scale.takeIf { it.isFinite() && it > 0f } ?: resources.displayMetrics.density
        fun px(value: Int) = (value * unit + .5f).toInt()
        inset = px(16)
        panel.removeAllViews()
        panel.background = GlassMaterial.panel(context, GlassVariant.REGULAR, 24f)
        panel.setPadding(px(12), px(16), px(12), px(12))
        panel.layoutParams = LayoutParams(1, 1, Gravity.TOP or Gravity.START).apply {
            topMargin = inset; marginStart = inset; bottomMargin = inset
        }
        requestLayout()
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), 0, px(8), px(12))
        }
        header.addView(text(channelNumber.toString(), 18, GlassTokens.textPrimary).apply {
            gravity = Gravity.CENTER
            background = rounded(GlassTokens.surfaceRaised, 8f)
            minimumWidth = px(48)
            minimumHeight = px(48)
            setPadding(px(12), px(8), px(12), px(8))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12) })
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(channel.name, 28, GlassTokens.textPrimary))
            addView(text("Programme guide · this channel", 14, GlassTokens.textSecondary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(header)

        val days = ChannelScheduleLogic.split(channel.programmes, nowMinute, time)
        val groups = listOf(days.today, days.tomorrow)
        day = if (days.today.isEmpty() && days.tomorrow.isNotEmpty()) 1 else 0
        val tabBar = LinearLayout(context).apply { setPadding(px(8), 0, px(8), px(12)) }
        val availableDays = if (days.tomorrow.isNotEmpty()) listOf(0, 1) else listOf(0)
        val tabViews = mutableListOf<TextView>()
        val scheduleList = ListView(context).apply {
            id = generateViewId()
            divider = null; dividerHeight = px(4)
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(px(32))
            clipToPadding = false
            setPadding(0, 0, 0, px(16))
        }
        list = scheduleList
        val empty = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(24), px(8), px(24))
            addView(text("No programme information for this channel.", 20, GlassTokens.textPrimary))
            addView(text("Add a guide in Settings → EPG.", 18, GlassTokens.textSecondary))
        }
        val body = FrameLayout(context).apply {
            addView(scheduleList, LayoutParams(-1, -1))
            addView(empty, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        }
        fun refreshTabs() {
            tabViews.forEachIndexed { index, tab ->
                val selected = availableDays[index] == day
                tab.isSelected = selected
                tab.background = when {
                    tab.hasFocus() -> GlassMaterial.lens(context, 100f)
                    selected -> rounded(GlassTokens.fillQuietStrong, 100f)
                    else -> GlassMaterial.focusable(context, 100f)
                }
                tab.setTextColor(if (tab.hasFocus()) GlassTokens.lensText else GlassTokens.textPrimary)
                tab.contentDescription = "${tab.text}${if (selected) ", selected" else ""}"
            }
        }
        fun changeDay(target: Int, focusList: Boolean) {
            day = target
            visibleRows = groups[day]
            scheduleList.adapter = ScheduleAdapter(visibleRows, nowMinute, time, unit)
            scheduleList.visibility = if (visibleRows.isEmpty()) GONE else VISIBLE
            empty.visibility = if (visibleRows.isEmpty()) VISIBLE else GONE
            refreshTabs()
            if (focusList && visibleRows.isNotEmpty()) scheduleList.requestFocus()
            val current = ChannelScheduleLogic.currentIndex(visibleRows, nowMinute)
            scheduleList.setSelection(current.takeIf { it >= 0 } ?: visibleRows.indexOfFirst {
                ChannelScheduleLogic.position(it, nowMinute) == ChannelScheduleLogic.Position.FUTURE
            }.coerceAtLeast(0))
        }
        availableDays.forEachIndexed { index, target ->
            val tab = text(if (target == 0) "Today" else "Tomorrow", 18, GlassTokens.textPrimary).apply {
                id = generateViewId()
                gravity = Gravity.CENTER
                isFocusable = true
                setPadding(px(20), px(10), px(20), px(10))
                setOnClickListener { changeDay(target, true) }
                setOnFocusChangeListener { _, focused ->
                    if (focused && day != target) changeDay(target, false)
                    refreshTabs()
                }
                setOnKeyListener { _, code, event ->
                    val direction = when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> 0
                    }
                    if (direction != 0) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            tabViews[(index + direction).coerceIn(availableDays.indices)].requestFocus()
                        }
                        true
                    } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && visibleRows.isNotEmpty()) {
                        if (event.action == KeyEvent.ACTION_DOWN) scheduleList.requestFocus()
                        true
                    } else false
                }
            }
            tabViews.add(tab)
            tabBar.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
        }
        tabs = tabViews
        panel.addView(tabBar)
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        if (updatedLabel != null) panel.addView(text("Stored guide · updated $updatedLabel", 14,
            GlassTokens.textSecondary).apply { setPadding(px(8), px(8), px(8), 0) })
        fun highlightRows() {
            for (i in 0 until scheduleList.childCount) {
                (scheduleList.getChildAt(i) as? ScheduleRow)?.highlight(scheduleList.hasFocus() &&
                    scheduleList.firstVisiblePosition + i == scheduleList.selectedItemPosition)
            }
        }
        scheduleList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = highlightRows()
            override fun onNothingSelected(parent: AdapterView<*>?) = highlightRows()
        }
        scheduleList.setOnFocusChangeListener { _, _ -> highlightRows() }
        scheduleList.setOnKeyListener { _, code, event ->
            when {
                code == KeyEvent.KEYCODE_DPAD_UP && scheduleList.selectedItemPosition <= 0 -> {
                    if (event.action == KeyEvent.ACTION_DOWN) tabs[availableDays.indexOf(day)].requestFocus()
                    true
                }
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER -> true
                else -> false
            }
        }
        changeDay(day, false)
        visibility = VISIBLE
        if (visibleRows.isEmpty()) tabs[availableDays.indexOf(day)].requestFocus() else scheduleList.requestFocus()
        post { highlightRows() }
        panel.alpha = 1f; panel.translationX = 0f
        if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) {
            panel.alpha = 0f; panel.translationX = -px(48).toFloat()
            panel.animate().alpha(1f).translationX(0f).setDuration(240)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun hide() {
        panel.animate().cancel()
        visibility = GONE
        dismiss = null
        list = null
        tabs = emptyList()
        visibleRows = emptyList()
        panel.removeAllViews()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        panel.layoutParams = (panel.layoutParams as LayoutParams).apply {
            this.width = (width * .60f).toInt().coerceAtMost((width - inset * 2).coerceAtLeast(1))
            this.height = (height - inset * 2).coerceAtLeast(1)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val candidate = super.focusSearch(focused, direction)
        var ancestor = candidate
        while (ancestor != null && ancestor !== this) ancestor = ancestor.parent as? View
        return if (ancestor === this) candidate else focused
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (visibility == VISIBLE && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismiss?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun text(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value; TvTypography.apply(this, size); setTextColor(color)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = GlassMaterial.dp(context, radius).toFloat()
    }

    private inner class ScheduleAdapter(val rows: List<Programme>, val now: Int,
        val time: (Int) -> String, val scale: Float) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? ScheduleRow) ?: ScheduleRow(scale)).apply {
                bind(rows[position], now, time)
                highlight(list?.hasFocus() == true && list?.selectedItemPosition == position)
            }
    }

    private inner class ScheduleRow(private val scale: Float) : LinearLayout(context) {
        private fun px(value: Int) = (value * scale + .5f).toInt()
        private val timing = text("", 14, GlassTokens.textSecondary)
        private val title = text("", 20, GlassTokens.textPrimary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val duration = text("", 14, GlassTokens.textSecondary)
        private val description = text("", 14, GlassTokens.textSecondary).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // The foundation accent is too light for small text on a pale chip; use a dark blue ink.
        private val nowChip = text("Now", 16, 0xFF174A78.toInt()).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(0xFFD6E9FF.toInt(), 100f)
            setPadding(px(8), px(2), px(8), px(2))
        }
        private val progress = FrameLayout(context).apply { setBackgroundColor(0xFF596777.toInt()) }
        private val progressFill = View(context).apply { setBackgroundColor(GlassTokens.accent) }
        private var past = false
        private var fraction = 0f

        init {
            orientation = VERTICAL
            isFocusable = false
            layoutParams = AbsListView.LayoutParams(-1, -2)
            setPadding(px(12), px(10), px(12), px(10))
            minimumHeight = px(76)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(timing, LinearLayout.LayoutParams(0, -2, 1f))
                addView(nowChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12) })
                addView(duration)
            })
            addView(title)
            addView(description)
            progress.addView(progressFill, FrameLayout.LayoutParams(0, -1))
            addView(progress, LinearLayout.LayoutParams(-1, px(3)).apply { topMargin = px(6) })
        }

        fun bind(programme: Programme, now: Int, time: (Int) -> String) {
            val position = ChannelScheduleLogic.position(programme, now)
            val current = position == ChannelScheduleLogic.Position.CURRENT
            past = position == ChannelScheduleLogic.Position.PAST
            timing.text = "${time(programme.start)} – ${time(programme.end)}"
            title.text = programme.title
            duration.text = ChannelScheduleLogic.durationLabel(programme)
            description.text = programme.description
            nowChip.visibility = if (current) VISIBLE else GONE
            progress.visibility = if (current) VISIBLE else GONE
            fraction = if (current) ((now.toLong() - programme.start).toFloat() / programme.duration).coerceIn(0f, 1f) else 0f
            contentDescription = "${programme.title}, ${time(programme.start)} to ${time(programme.end)}${if (current) ", now" else ""}"
            highlight(false)
        }

        fun highlight(focused: Boolean) {
            background = if (focused) GlassMaterial.lens(context) else GlassMaterial.focusable(context)
            title.setTextColor(if (focused) GlassTokens.lensText else if (past) GlassTokens.textSecondary else GlassTokens.textPrimary)
            listOf(timing, duration, description).forEach {
                it.setTextColor(if (focused) GlassTokens.lensText else GlassTokens.textSecondary)
            }
            description.visibility = if (focused && description.text.isNotBlank()) VISIBLE else GONE
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateProgress(w)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val filled = ((width - paddingLeft - paddingRight) * fraction).toInt()
            progressFill.layout(0, 0, filled, progress.height)
        }

        private fun updateProgress(width: Int) {
            progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply {
                this.width = ((width - paddingLeft - paddingRight) * fraction).toInt()
            }
        }
    }
}
