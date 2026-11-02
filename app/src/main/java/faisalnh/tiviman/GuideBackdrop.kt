package faisalnh.tiviman

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin

/** Static canvas and ambient light for guide panels, independent of the video surface. */
class GuideBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lights = emptyList<RadialGradient>()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val ambient = 0x661E2A36
        val transparentAmbient = ambient and 0x00FFFFFF
        val radius = maxOf(w, h).coerceAtLeast(1) * .7f
        lights = listOf(.9f to .1f, .1f to .9f).map { (x, y) ->
            RadialGradient(w * x, h * y, radius, ambient, transparentAmbient, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(GlassTokens.canvas)
        lights.forEach { light ->
            paint.shader = light
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
    }
}

object GuidePanels {
    /** Keeps destinations and Settings together at the vertical center of the rail. */
    fun rail(context: Context, scale: Float, destinations: List<Pair<String, String>>, selected: Int,
        settingsLabel: String = "Settings", onItem: (Int) -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassMaterial.panel(context, GlassVariant.REGULAR, 24f)
        val inset = (8 * scale).toInt()
        setPadding(inset, inset, inset, inset)
        minimumWidth = (48 * scale).toInt()
        fun item(key: String, label: String, index: Int): View = RailIcon(context, key, scale).apply {
            contentDescription = label
            isFocusable = true
            isFocusableInTouchMode = true
            isSelected = index == selected
            val base = GradientDrawable().apply {
                cornerRadius = 16 * scale
                setColor(GlassTokens.fillQuietStrong)
            }
            background = if (isSelected) LayerDrawable(arrayOf(base,
                GlassMaterial.focusable(context, 16f, quietFill = false)))
            else GlassMaterial.focusable(context, 16f, quietFill = false)
            setOnClickListener { onItem(index) }
        }
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        destinations.forEachIndexed { index, (key, label) ->
            addView(item(key, label, index), LinearLayout.LayoutParams(-1, (40 * scale).toInt()))
        }
        addView(item("settings", settingsLabel, destinations.size),
            LinearLayout.LayoutParams(-1, (40 * scale).toInt()))
        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
    }

    /** Returns an empty vertical details panel for the caller's programme content. */
    fun detailsCard(context: Context, scale: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassMaterial.panel(context, GlassVariant.REGULAR, 24f)
        val inset = (12 * scale).toInt()
        setPadding(inset, inset, inset, inset)
    }

    /** Builds a 24dp ruler; label and now positions are local physical pixels, not dp. */
    fun ruler(context: Context, scale: Float, labels: List<Pair<Float, String>>,
        nowX: Float?, nowLabel: String?): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                14f, resources.displayMetrics)
        }
        init {
            background = GlassMaterial.panel(context, GlassVariant.REGULAR, 12f)
            minimumHeight = maxOf((24 * scale).toInt(),
                (paint.fontMetrics.bottom - paint.fontMetrics.top + 4 * scale).toInt())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = (labels.map { it.second } + listOfNotNull(nowLabel)).joinToString(", ")
        }
        override fun onDraw(canvas: Canvas) {
            val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2
            fun left(x: Float, text: String) = x.coerceIn(12 * scale,
                maxOf(12 * scale, width - 12 * scale - paint.measureText(text)))
            val nowLeft = if (nowX != null && nowLabel != null) left(nowX + 6 * scale, nowLabel) else null
            paint.color = GlassTokens.textSecondary
            labels.forEach { (x, text) ->
                val fits = x >= 12 * scale && x + paint.measureText(text) <= width - 12 * scale
                val clearOfNow = nowLeft == null ||
                    x + paint.measureText(text) + 60 * scale < nowLeft ||
                    x > nowLeft + paint.measureText(nowLabel.orEmpty()) + 60 * scale
                if (fits && clearOfNow) canvas.drawText(text, x, baseline, paint)
            }
            if (nowX != null && nowX in 0f..width.toFloat()) {
                paint.color = GlassTokens.accent
                canvas.drawCircle(nowX, height / 2f, 2 * scale, paint)
                if (nowLabel != null && nowLeft != null) canvas.drawText(nowLabel, nowLeft, baseline, paint)
            }
        }
    }

    private class RailIcon(context: Context, private val key: String, private val scale: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Button::class.java.name
        }
        override fun onDraw(canvas: Canvas) {
            paint.color = GlassMaterial.focusText().getColorForState(drawableState, GlassTokens.textPrimary)
            val save = canvas.save()
            canvas.translate(width / 2f - 12 * scale, height / 2f - 12 * scale)
            canvas.scale(scale, scale)
            path.reset()
            when (key) {
                "search" -> {
                    canvas.drawCircle(10f, 10f, 6f, paint)
                    canvas.drawLine(14.5f, 14.5f, 21f, 21f, paint)
                }
                "tv" -> {
                    canvas.drawRoundRect(3f, 6f, 21f, 19f, 2f, 2f, paint)
                    canvas.drawLine(8f, 2f, 12f, 6f, paint)
                    canvas.drawLine(16f, 2f, 12f, 6f, paint)
                }
                "film" -> {
                    canvas.drawRoundRect(3f, 3f, 21f, 21f, 1f, 1f, paint)
                    canvas.drawLine(7f, 3f, 7f, 21f, paint)
                    canvas.drawLine(17f, 3f, 17f, 21f, paint)
                    for (y in 7..17 step 5) {
                        canvas.drawLine(3f, y.toFloat(), 7f, y.toFloat(), paint)
                        canvas.drawLine(17f, y.toFloat(), 21f, y.toFloat(), paint)
                    }
                }
                "record" -> {
                    canvas.drawCircle(12f, 12f, 9f, paint)
                    canvas.drawCircle(12f, 12f, 4f, paint)
                }
                "bookmark" -> {
                    path.moveTo(6f, 3f); path.lineTo(18f, 3f); path.lineTo(18f, 21f)
                    path.lineTo(12f, 17f); path.lineTo(6f, 21f); path.close()
                    canvas.drawPath(path, paint)
                }
                "settings" -> {
                    for (i in 0 until 48) {
                        val angle = i * Math.PI / 24
                        val radius = if (i % 6 in 1..4) 10f else 7.5f
                        val x = 12f + cos(angle).toFloat() * radius
                        val y = 12f + sin(angle).toFloat() * radius
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawCircle(12f, 12f, 3f, paint)
                }
            }
            canvas.restoreToCount(save)
        }
    }
}
