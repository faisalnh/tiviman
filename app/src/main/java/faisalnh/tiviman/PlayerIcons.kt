package faisalnh.tiviman

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

internal class PlayerIconDrawable(context: Context, name: String) : android.graphics.drawable.Drawable() {
    private val icon = PlayerIcon(context, name)
    override fun draw(canvas: Canvas) {
        icon.ink = GlassMaterial.focusText().getColorForState(state, GlassTokens.textPrimary)
        icon.layout(0, 0, bounds.width(), bounds.height())
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        icon.draw(canvas)
        canvas.restoreToCount(saved)
    }
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Drawable opacity is not used on modern Android")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Original action silhouettes on a 28 dp drawing grid, independent of installed fonts. */
internal class PlayerIcon(context: Context, private val name: String) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    var ink: Int? = null
        set(value) { field = value; invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 28f, size / 28f)
        pen.color = ink ?: GlassMaterial.focusText().getColorForState(drawableState, GlassTokens.textPrimary)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, pen)
        fun points(vararg coordinates: Float, closed: Boolean = false) {
            path.reset(); path.moveTo(coordinates[0], coordinates[1])
            for (i in 2 until coordinates.size step 2) path.lineTo(coordinates[i], coordinates[i + 1])
            if (closed) path.close()
            canvas.drawPath(path, pen)
        }
        fun screen() = canvas.drawRoundRect(4f, 6f, 24f, 21f, 2f, 2f, pen)
        when (name) {
            "Search" -> {
                canvas.drawCircle(12f, 12f, 6.5f, pen); line(17f, 17f, 23f, 23f)
            }
            "guide" -> {
                canvas.drawRoundRect(4f, 5f, 24f, 23f, 1.5f, 1.5f, pen)
                line(4f, 11f, 24f, 11f); line(10f, 11f, 10f, 23f)
                line(10f, 17f, 24f, 17f); line(18f, 11f, 18f, 17f)
            }
            "Channels list" -> for (y in listOf(7f, 14f, 21f)) {
                canvas.drawRect(4f, y - 1, 7f, y + 1, pen); line(11f, y, 24f, y)
            }
            "Recordings" -> {
                canvas.drawCircle(14f, 14f, 10f, pen)
                pen.style = Paint.Style.FILL
                canvas.drawCircle(14f, 14f, 4.5f, pen)
                pen.style = Paint.Style.STROKE
            }
            "Playlists" -> {
                points(3f, 8f, 3f, 5f, 11f, 5f, 14f, 8f, 25f, 8f, 25f, 23f, 3f, 23f, closed = true)
                line(8f, 13f, 20f, 13f); line(8f, 18f, 20f, 18f)
            }
            "About" -> {
                canvas.drawCircle(14f, 14f, 10f, pen)
                line(14f, 13f, 14f, 20f)
                pen.style = Paint.Style.FILL
                canvas.drawCircle(14f, 8f, 1f, pen)
                pen.style = Paint.Style.STROKE
            }
            "Multiview" -> for (x in listOf(4f, 16f)) for (y in listOf(5f, 16f))
                canvas.drawRoundRect(x, y, x + 8, y + 7, 1f, 1f, pen)
            "Picture-in-picture" -> {
                screen(); canvas.drawRect(15f, 13f, 21f, 18f, pen)
                points(7f, 16f, 11f, 12f, 11f, 15f)
            }
            "Video tracks" -> {
                screen(); points(12f, 10f, 18f, 13.5f, 12f, 17f, closed = true)
                line(8f, 24f, 20f, 24f)
            }
            "Audio tracks" -> {
                points(4f, 11f, 9f, 11f, 15f, 6f, 15f, 22f, 9f, 17f, 4f, 17f, closed = true)
                canvas.drawArc(13f, 7f, 25f, 21f, -65f, 130f, false, pen)
                canvas.drawArc(15f, 10f, 21f, 18f, -65f, 130f, false, pen)
            }
            "Audio offset" -> {
                points(3f, 11f, 7f, 11f, 12f, 7f, 12f, 21f, 7f, 17f, 3f, 17f, closed = true)
                line(17f, 10f, 25f, 10f); line(21f, 6f, 21f, 14f)
                line(17f, 21f, 25f, 21f)
            }
            "Closed captions" -> {
                screen()
                // Open bracket strokes suggest paired caption letters without font glyphs.
                for (x in listOf(7f, 15f)) {
                    path.reset(); path.moveTo(x + 5, 10f); path.cubicTo(x - 1, 7f, x - 1, 20f, x + 5, 17f)
                    canvas.drawPath(path, pen)
                }
            }
            "Display mode" -> {
                screen(); line(10f, 24f, 18f, 24f); line(14f, 21f, 14f, 24f)
                points(7f, 12f, 7f, 9f, 10f, 9f); points(18f, 18f, 21f, 18f, 21f, 15f)
            }
            "Sleep timer" -> {
                canvas.drawCircle(14f, 15f, 9f, pen); line(14f, 9f, 14f, 15f); line(14f, 15f, 18f, 17f)
                line(11f, 2f, 17f, 2f); line(14f, 2f, 14f, 6f)
            }
            "history" -> {
                canvas.drawArc(5f, 5f, 24f, 24f, 205f, 305f, false, pen)
                points(3f, 6f, 4f, 12f, 10f, 11f)
                points(14f, 9f, 14f, 15f, 19f, 18f)
            }
            "retry" -> {
                canvas.drawArc(5f, 5f, 23f, 23f, -55f, 300f, false, pen)
                points(17f, 3f, 23f, 6f, 21f, 12f)
            }
            "schedule", "EPG" -> {
                canvas.drawRoundRect(4f, 6f, 24f, 24f, 2f, 2f, pen)
                line(4f, 12f, 24f, 12f); line(9f, 3f, 9f, 9f); line(19f, 3f, 19f, 9f)
                for (x in listOf(9f, 14f, 19f)) line(x, 16f, x, 19f)
            }
            "Add to Favorites" -> {
                // A bookmark marks the saved channel rather than a decorative star.
                points(7f, 4f, 21f, 4f, 21f, 24f, 14f, 19f, 7f, 24f, closed = true)
            }
            "Channel options" -> {
                for (y in listOf(6f, 14f, 22f)) {
                    line(4f, y, 24f, y)
                    val x = if (y == 14f) 18f else 10f
                    canvas.drawRect(x - 2, y - 2, x + 2, y + 2, pen)
                }
            }
            "Settings" -> {
                path.reset()
                for (i in 0 until 32) {
                    val angle = Math.PI * i / 16 - Math.PI / 2
                    val radius = if (i % 4 in 1..2) 11f else 8.5f
                    val x = 14f + kotlin.math.cos(angle).toFloat() * radius
                    val y = 14f + kotlin.math.sin(angle).toFloat() * radius
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); canvas.drawPath(path, pen); canvas.drawCircle(14f, 14f, 3.5f, pen)
            }
            "down" -> points(8f, 11f, 14f, 17f, 20f, 11f)
            "eye", "eye off" -> {
                path.reset(); path.moveTo(3f, 14f)
                path.quadTo(14f, 3f, 25f, 14f); path.quadTo(14f, 25f, 3f, 14f); path.close()
                canvas.drawPath(path, pen); canvas.drawCircle(14f, 14f, 3.5f, pen)
                if (name == "eye off") line(5f, 23f, 23f, 5f)
            }
        }
        canvas.restore()
    }
}
