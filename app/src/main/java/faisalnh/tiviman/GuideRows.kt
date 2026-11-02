package faisalnh.tiviman

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Recycles vertical rows; only attached cells are updated on a focus/time-window change. */
class GuideRows(
    context: Context,
    private val select: (Int, Int, Boolean) -> Unit,
) : RecyclerView(context) {
    private var state = GuideState()
    private var scale = 1f
    private var geometry = GuideGeometry(1f)
    private var windowStart = 0
    private var binding = false
    private var focusGeneration = 0
    private val rows = RowsAdapter()
    private val linear = LinearLayoutManager(context)

    init {
        background = GlassMaterial.panel(context, GlassVariant.REGULAR, 24f)
        clipToPadding = true
        clipToOutline = true
        layoutManager = linear
        adapter = rows
        itemAnimator = null
        setHasFixedSize(true)
        preserveFocusAfterLayout = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setItemViewCacheSize(2)
        addOnChildAttachStateChangeListener(object : OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val holder = getChildViewHolder(view) as RowHolder
                val position = holder.bindingAdapterPosition
                if (position != NO_POSITION) holder.bind(state.channels[position])
            }
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    fun show(next: GuideState, newScale: Float, start: Int) {
        val changedGroup = state.group != next.group || state.catalog != next.catalog ||
            (next.group == next.favoritesGroup && state.favorites != next.favorites)
        state = next
        val nextGeometry = GuideGeometry(TvTypography.expansion(context, newScale))
        val resized = scale != newScale || geometry != nextGeometry
        scale = newScale
        val inset = (12 * scale).toInt()
        setPadding(inset, inset, inset, inset)
        geometry = nextGeometry
        windowStart = start
        visibility = if (next.pane == Pane.FULLSCREEN) View.GONE else View.VISIBLE
        if (changedGroup || resized) rows.notifyDataSetChanged()
        else {
            // No adapter invalidation for ordinary navigation: holders and native focus survive.
            for (i in 0 until childCount) {
                val holder = getChildViewHolder(getChildAt(i)) as RowHolder
                val position = holder.bindingAdapterPosition
                if (position in state.channels.indices) holder.bind(state.channels[position])
            }
        }
        val generation = ++focusGeneration
        if (next.pane != Pane.GUIDE) return
        val position = next.channels.indexOfFirst { it.id == next.channelId }
        val first = linear.findFirstCompletelyVisibleItemPosition()
        val last = linear.findLastCompletelyVisibleItemPosition()
        if (changedGroup || resized || first == NO_POSITION || position < first || position > last) {
            // Compute the last whole row from the viewport, not a baseline row count.
            linear.scrollToPositionWithOffset(position.coerceAtLeast(0),
                if (position > last && last != NO_POSITION)
                                    geometry.selectedOffset((layoutParams?.height?.takeIf { it > 0 } ?: height) - paddingTop - paddingBottom,
                                                            (geometry.rowPitch * scale).toInt()) else 0)
        }
        post {
            if (generation == focusGeneration && state.pane == Pane.GUIDE) {
                (findViewHolderForAdapterPosition(position) as? RowHolder)?.focusCell(state.programmeIndex)
            }
        }
    }

    private fun bounds(x: Int, y: Int, w: Int, h: Int) = FrameLayout.LayoutParams(
        (w * scale).toInt(), (h * scale).toInt()
    ).apply { leftMargin = (x * scale).toInt(); topMargin = (y * scale).toInt() }

    private fun label() = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private inner class RowHolder(val row: FrameLayout) : ViewHolder(row) {
        val identity = label()
        val separator = View(context).apply { setBackgroundColor(GlassTokens.fillQuiet) }
        val timeline = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = 12 * context.resources.displayMetrics.density
            }
            clipToOutline = true
        }
        val cells = (0..3).map { label() }
        var channelId = -1

        init {
            row.addView(identity)
            row.addView(separator)
            row.addView(timeline)
            cells.forEachIndexed { index, cell ->
                timeline.addView(cell)
                cell.isFocusable = true
                cell.isFocusableInTouchMode = true

                cell.setOnFocusChangeListener { _, focused ->
                    if (focused && !binding && state.pane == Pane.GUIDE && channelId >= 0 &&
                        (state.channelId != channelId || state.programmeIndex != index)) {
                        select(channelId, index, false)
                    }
                }

            }
        }

        fun focusCell(index: Int) { cells[index].requestFocus() }

        fun bind(channel: Channel) {
            binding = true
            try {
                channelId = channel.id
                // RecyclerView owns LayoutParams' holder link; never replace attached params.
                val params = row.layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, (geometry.rowPitch * scale).toInt())
                params.height = (geometry.rowPitch * scale).toInt()
                row.layoutParams = params
                identity.layoutParams = bounds(0, 0, geometry.identityWidth, geometry.cellHeight)
                separator.layoutParams = bounds(0, geometry.rowPitch - 1, geometry.identityWidth, 1)
                timeline.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (geometry.cellHeight * scale).toInt()
                ).apply { leftMargin = (geometry.timelineStart * scale).toInt() }
                val focused = channel.id == state.channelId
                val rowColor = if (focused) GlassTokens.fillQuietStrong else GlassTokens.fillQuiet
                val number = "${channel.id + 1}"
                val marker = if (channel.id == state.tunedId) "▶ " else ""
                identity.text = android.text.SpannableString("$number   $marker${channel.name}").apply {
                    setSpan(android.text.style.ForegroundColorSpan(GlassTokens.textSecondary), 0, number.length, 0)
                    if (marker.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(GlassTokens.accent),
                        number.length + 3, number.length + 3 + marker.length, 0)
                }
                TvTypography.apply(identity, 14)
                identity.setPadding(0, 0, (6 * scale).toInt(), 0)
                identity.setTextColor(GlassTokens.textPrimary)
                cells.forEachIndexed { index, cell ->
                    val programme = channel.programmes.getOrNull(index)
                    cell.visibility = if (programme == null) View.GONE else View.VISIBLE
                    cell.setOnClickListener(null)
                    cell.isClickable = false
                    if (programme != null) {
                        if (state.pane == Pane.GUIDE && programme.isCurrentAt(state.nowMinute)) {
                            cell.setOnClickListener { select(channelId, index, true) }
                        }
                        val left = ((programme.start.toLong() - windowStart) * 320 / 60).coerceIn(-100000, 100000).toInt()
                        val width = (programme.duration.toLong() * 320 / 60).coerceIn(1, 100000).toInt()
                        cell.layoutParams = bounds(left, 0, (width - 2).coerceAtLeast(1), geometry.cellHeight)
                        cell.text = programme.title
                        cell.contentDescription = "${channel.name}, ${programme.title}, ${state.time(programme.start)} to ${state.time(programme.end)}" +
                            (if (channel.id == state.tunedId) ", tuned" else "") +
                            (programme.unavailableActionExplanation(state.nowMinute)?.let { ". $it" } ?: "")
                        val active = focused && index == state.programmeIndex && state.pane == Pane.GUIDE
                        cell.isSelected = active
                        cell.isFocusable = state.pane == Pane.GUIDE && left + width > 0 && left < 690
                        style(cell, if (active) GlassTokens.lens else rowColor,
                            if (active) GlassTokens.lensText else GlassTokens.textPrimary, active)
                    }
                }
            } finally { binding = false }
        }

        private fun style(label: TextView, fill: Int, foreground: Int, active: Boolean) {
            TvTypography.apply(label, 14)
            label.setPadding((8 * scale).toInt(), 0, (6 * scale).toInt(), 0)
            label.setTextColor(foreground)
            label.background = object : GradientDrawable() {
                private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (GlassTokens.fillQuiet and 0x00FFFFFF) or (153 shl 24)
                    strokeWidth = scale
                }
                override fun draw(canvas: Canvas) {
                    super.draw(canvas)
                    if (active) canvas.drawLine(bounds.left + 6 * scale, bounds.top + scale / 2,
                        bounds.right - 6 * scale, bounds.top + scale / 2, highlight)
                }
            }.apply { setColor(fill); cornerRadius = 6 * scale }
        }
    }

    private inner class RowsAdapter : Adapter<RowHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = state.channels[position].id.toLong()
        override fun getItemCount() = state.channels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(FrameLayout(context))
        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(state.channels[position])
    }
}
