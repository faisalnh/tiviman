package faisalnh.tiviman

import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class FullscreenChrome(context: Context) : FrameLayout(context) {
    private var binding = false
    private var generation = 0
    private var toolbarCapsule: View? = null
    private var toolbarSettings: ToolbarItem? = null

    fun toolbarTop(): Int? = toolbarCapsule?.takeIf { it.isLaidOut }?.let {
        val area = android.graphics.Rect(0, 0, it.width, it.height)
        offsetDescendantRectToMyCoords(it, area)
        area.top
    }

    fun recedeToolbar(receded: Boolean) {
        toolbarCapsule?.apply {
            background = GlassMaterial.panel(context, if (receded) GlassVariant.RECEDED else GlassVariant.CLEAR,
                if (receded) 24f else 1000f)
            elevation = 0f
        }
        toolbarSettings?.showOpener(receded)
    }

    private fun dp(value: Int) = GlassMaterial.dp(context, value.toFloat())
    private fun label(value: String, size: Int, color: Int = GlassTokens.textPrimary, medium: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            TvTypography.apply(this, size)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }

    fun show(state: GuideState, newScale: Float, privatePlaylist: Boolean, status: String,
        badges: String, failed: Boolean, select: (Boolean, Int) -> Unit, activate: (Int) -> Unit,
        openToolbar: (Int) -> Unit, sleepLabel: String, favorite: Boolean = false,
        pictureInPicture: Boolean = false, showSchedule: Boolean = false) {
        binding = true
        val token = ++generation
        val previousScrollX = (toolbarCapsule as? ViewGroup)?.getChildAt(0)
            ?.let { it as? HorizontalScrollView }?.scrollX
        removeAllViews()
        toolbarCapsule = null
        toolbarSettings = null
        visibility = if (state.pane == Pane.FULLSCREEN && state.overlay) VISIBLE else GONE
        if (visibility != VISIBLE) { binding = false; return }
        background = null
        val expansion = TvTypography.expansion(context, newScale)
        // Keep the legacy two-region layout contract without painting a header over video.
        addView(View(context), LayoutParams(0, 0))
        val shelf = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(shelf, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(24), 0, dp(24), dp(16))
        })
        val programmes = state.tunedChannel.programmes
        val current = programmes.firstOrNull { it.isCurrentAt(state.nowMinute) } ?: programmes.firstOrNull()
        val next = current?.let { programmes.getOrNull(programmes.indexOf(it) + 1) }
        val times = if (state.realEpg && current != null)
            "${state.time(current.start)} – ${state.time(current.end)} • XMLTV"
        else if (privatePlaylist) "No EPG configured"
        else if (current != null) "${Fixtures.time(current.start)} – ${Fixtures.time(current.end)} • Synthetic guide"
        else "Synthetic guide"
        val nextLine = if (privatePlaylist && !state.realEpg) "Next: No information"
            else "Next: ${next?.title ?: "No information"}"
        val progress = current?.takeIf {
            (state.realEpg || !privatePlaylist) && it.duration > 0 && it.isCurrentAt(state.nowMinute)
        }?.let { (state.nowMinute - it.start).toFloat() / it.duration }
        var target: View? = null

        fun identity(size: Int) = label("${state.tunedId + 1}", size, medium = true).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(GlassTokens.surfaceRaised); cornerRadius = dp(8).toFloat()
            }
            contentDescription = "Channel ${state.tunedId + 1}"
        }
        fun chips() = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            listOf(status, badges).flatMap { it.split(" • ") }.filter { it.isNotBlank() }.forEach { value ->
                val chip = FrameLayout(context).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply {
                        setColor(GlassTokens.surfaceRaised); cornerRadius = dp(8).toFloat()
                    }
                    addView(label(value, 12).apply { setSingleLine(true); ellipsize = null }, LayoutParams(-2, -2))
                }
                addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })
            }
        }
        fun addProgress(body: LinearLayout) {
            progress?.let {
                body.addView(ProgrammeProgress(context, it), LinearLayout.LayoutParams(-1, dp(3)).apply {
                    topMargin = dp(4); bottomMargin = dp(4)
                })
            }
        }

        if (!state.toolbar) {
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(12))
                background = GlassMaterial.panel(context, GlassVariant.REGULAR, 24f)
            }
            shelf.addView(panel, LinearLayout.LayoutParams(-1, -2))
            val details = object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(280), MeasureSpec.AT_MOST))
                }
            }.apply { tag = "programme-details"; isFillViewport = false }
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            details.addView(body)
            panel.addView(details, LinearLayout.LayoutParams(-1, -2))

            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(state.tunedChannel.group, 14, GlassTokens.textSecondary), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT).format(java.util.Date()), 14, GlassTokens.textSecondary))
            body.addView(top)
            val channel = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            channel.addView(identity(18), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
            channel.addView(label(state.tunedChannel.name, 18), LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(channel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            body.addView(label(current?.title ?: "No information", 28, medium = true))
            body.addView(label(times, 14, GlassTokens.textSecondary))
            body.addView(label(nextLine, 14, GlassTokens.textSecondary))
            addProgress(body)
            // Preserve full chip labels without letting long stream metadata escape the panel.
            val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFocusable = false }
            chipScroll.addView(chips()); body.addView(chipScroll)
            if (state.realEpg) body.addView(label(current?.description.orEmpty(), 15, GlassTokens.textSecondary))

            val actions = buildList {
                add("TV guide" to "guide"); add("History" to "history")
                if (showSchedule) add("Schedule" to "schedule")
                if (failed) add("Retry" to "retry")
            }
            val actionScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFocusable = false }
            val row = LinearLayout(context)
            actionScroll.addView(row); panel.addView(actionScroll, LinearLayout.LayoutParams(-1, -2))
            actions.forEachIndexed { index, (name, icon) ->
                val tile = PlayerAction(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = GlassMaterial.focusable(context)
                    contentDescription = name
                    setOnClickListener { activate(index) }
                    setOnFocusChangeListener { _, focused -> if (focused && !binding) select(false, index) }
                }
                tile.addView(PlayerIcon(context, icon).apply { isDuplicateParentStateEnabled = true },
                    LinearLayout.LayoutParams(dp(28), dp(28)))
                tile.addView(label(name, 18).apply {
                    gravity = Gravity.CENTER; isDuplicateParentStateEnabled = true; setTextColor(GlassMaterial.focusText())
                }, LinearLayout.LayoutParams(-1, -2))
                row.addView(tile, LinearLayout.LayoutParams(dp((160 * expansion).toInt()), -2).apply { rightMargin = dp(8) })
                if (state.action == index) target = tile
            }
            panel.addView(PlayerIcon(context, "down").apply { ink = GlassTokens.textSecondary },
                LinearLayout.LayoutParams(dp(24), dp(20)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        } else {
            val strip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(24), dp(4), dp(24), dp(4))
                background = GlassMaterial.panel(context, GlassVariant.REGULAR, 20f)
                minimumHeight = dp(60)
            }
            shelf.addView(strip, LinearLayout.LayoutParams(-1, dp((60 * expansion).toInt())).apply { bottomMargin = dp(8) })
            strip.addView(identity(18), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(current?.title ?: "No information", 18, medium = true))
            info.addView(label("$times · $nextLine", 14, GlassTokens.textSecondary))
            addProgress(info)
            val stripDetails = ScrollView(context).apply {
                isFocusable = false; isVerticalScrollBarEnabled = false
                addView(info)
            }
            strip.addView(stripDetails, LinearLayout.LayoutParams(0, -1, 1f))
            val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFocusable = false }
            chipScroll.addView(chips())
            strip.addView(chipScroll, LinearLayout.LayoutParams(dp((260 * expansion).toInt()), -2).apply { leftMargin = dp(12) })
            chipScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val chipRow = chipScroll.getChildAt(0) as LinearLayout
                var used = 0
                val excess = mutableListOf<View>()
                for (index in 0 until chipRow.childCount) {
                    val chip = chipRow.getChildAt(index)
                    chip.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
                    val required = chip.measuredWidth + dp(4)
                    if (used + required <= chipScroll.width) used += required else excess.add(chip)
                }
                excess.forEach(chipRow::removeView)
            }
            val capsule = object : FrameLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    // The scrolling viewport stays inside the pill's straight section, even at large fonts.
                    val inset = maxOf(dp(40), measuredHeight / 2 + dp(8))
                    if (paddingLeft != inset) {
                        setPadding(inset, paddingTop, inset, paddingBottom)
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }
            }.apply {
                setPadding(dp(40), dp(16), dp(40), dp(16))
                background = GlassMaterial.panel(context, GlassVariant.CLEAR, 1000f)
                minimumHeight = dp(64)
            }
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; isFocusable = false
                isHorizontalFadingEdgeEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isSmoothScrollingEnabled = true
                clipToPadding = true
            }
            capsule.addView(scroll, LayoutParams(-1, -2))
            toolbarCapsule = capsule
            shelf.addView(capsule, LinearLayout.LayoutParams(-1, -2))
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            scroll.addView(row)
            PlayerToolbar.entries.forEachIndexed { index, name ->
                if (index in listOf(3, 5, 9, 11)) row.addView(View(context).apply {
                    setBackgroundColor((GlassTokens.textPrimary and 0x00FFFFFF) or (36 shl 24))
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(1), dp(32)).apply { setMargins(dp(12), 0, dp(12), 0) })
                val caption = if (index == 11 && favorite) "Remove from Favorites" else name
                val available = index in TrackControls.types || index in listOf(0, 1, 2, 3, 7, 9, 10, 11, 12, 13) || (index == 4 && pictureInPicture)
                val tile = ToolbarItem(context, name, caption, if (index == 10) sleepLabel else if (!available) "Unavailable" else "").apply {
                    contentDescription = if (available) caption else if (index == 4) "$name, unavailable on this device" else "$name, unavailable, not implemented"
                    isClickable = available
                    if (available) setOnClickListener { openToolbar(index) }
                    setOnFocusChangeListener { view, focused ->
                        (view as ToolbarItem).updateFocus()
                        if (focused && !binding) select(true, index)
                    }
                }
                if (index == 13) toolbarSettings = tile
                row.addView(tile, LinearLayout.LayoutParams(dp((112 * expansion).toInt()), -2))
                if (state.toolbarAction == index) target = tile
            }
            val selected = target
            post {
                if (token == generation) {
                    val items = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<ToolbarItem>()
                    val last = items.lastOrNull()
                    if (selected != null && last != null) {
                        val previous = (previousScrollX ?: 0).coerceIn(0,
                            (last.right - scroll.width).coerceAtLeast(0))
                        val destination = when {
                            selected.left < previous -> selected.left
                            selected.right > previous + scroll.width -> selected.right - scroll.width
                            else -> previous
                        }.coerceAtLeast(0)
                        binding = true
                        selected.requestFocus()
                        scroll.scrollTo(previous, 0)
                        if (previousScrollX == null) scroll.scrollTo(destination, 0)
                        else scroll.smoothScrollTo(destination, 0)
                        binding = false
                    }
                }
            }
        }
        target?.requestFocus()
        binding = false
    }
}

private open class PlayerAction(context: Context) : LinearLayout(context) {
    init { isFocusable = true; isFocusableInTouchMode = true; isClickable = true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}

private class ToolbarItem(context: Context, icon: String, caption: String, detail: String) : PlayerAction(context) {
    private val circle = FrameLayout(context)
    private val title = TextView(context)
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER; isDuplicateParentStateEnabled = true
    }
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        val dp = { value: Int -> GlassMaterial.dp(context, value.toFloat()) }
        // Reserve growth inside each slot so focused neighbours never move.
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addView(content, LayoutParams(-1, -2))
        circle.background = GlassMaterial.focusable(context, 20f, quietFill = false)
        circle.isDuplicateParentStateEnabled = true
        circle.addView(PlayerIcon(context, icon).apply { isDuplicateParentStateEnabled = true },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        content.addView(circle, LayoutParams(dp(40), dp(40)))
        title.text = caption; title.setTextColor(GlassTokens.textPrimary); TvTypography.apply(title, 11)
        title.gravity = Gravity.CENTER
        title.maxLines = 2
        title.ellipsize = null
        content.addView(title, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (detail.isNotEmpty()) content.addView(TextView(context).apply {
            text = detail; setTextColor(GlassTokens.textPrimary); TvTypography.apply(this, 11)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); gravity = Gravity.CENTER
        }, LayoutParams(-1, -2))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        content.pivotX = content.width / 2f; content.pivotY = content.height / 2f
    }
    fun showOpener(shown: Boolean) {
        circle.background = if (shown) GradientDrawable().apply {
            setColor(GlassTokens.surfaceRaised)
            cornerRadius = GlassMaterial.dp(context, 20f).toFloat()
            setStroke(GlassMaterial.dp(context, 1f), GlassTokens.lens)
        } else GlassMaterial.focusable(context, 20f, quietFill = false)
    }

    fun updateFocus() {
        title.typeface = Typeface.create(if (isFocused) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        updateScale()
    }
    private fun updateScale() {
        content.animate().cancel()
        val motion = playerMotionEnabled(context)
        val scale = if (!motion) 1f else if (isPressed) .96f else if (isFocused) 1.1f else 1f
        content.pivotX = content.width / 2f; content.pivotY = content.height / 2f
        if (motion) content.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        else { content.scaleX = scale; content.scaleY = scale }
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updateScale()
    }
}

private fun playerMotionEnabled(context: Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private class ProgrammeProgress(context: Context, private val fraction: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        paint.color = GlassTokens.fillQuietStrong
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = GlassTokens.accent
        canvas.drawRect(0f, 0f, width * fraction.coerceIn(0f, 1f), height.toFloat(), paint)
    }
}

/** Standalone player loading overlay. The host chooses its position and visibility. */
class BufferingIndicator(context: Context) : FrameLayout(context) {
    private val ring = BufferingRing(context)
    init {
        val size = GlassMaterial.dp(context, 72f)
        minimumWidth = size; minimumHeight = size
        background = GlassMaterial.panel(context, GlassVariant.REGULAR, 1000f)
        contentDescription = "Buffering"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(ring, LayoutParams(GlassMaterial.dp(context, 48f), GlassMaterial.dp(context, 48f), Gravity.CENTER))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = GlassMaterial.dp(context, 72f)
        super.onMeasure(MeasureSpec.makeMeasureSpec(resolveSize(size, widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(resolveSize(size, heightMeasureSpec), MeasureSpec.EXACTLY))
    }
}

private class BufferingRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassTokens.accent; style = Paint.Style.STROKE
        strokeWidth = GlassMaterial.dp(context, 3f).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { invalidate() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        invalidate()
    }
    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(observer)
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val moving = isAttachedToWindow && isShown && playerMotionEnabled(context)
        val angle = if (moving) (SystemClock.uptimeMillis() % 1200) * .3f else -90f
        val inset = paint.strokeWidth / 2
        canvas.drawArc(inset, inset, width - inset, height - inset, angle, 270f, false, paint)
        if (moving) postInvalidateOnAnimation()
    }
}
