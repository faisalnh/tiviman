package faisalnh.tiviman

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.function.Consumer
import kotlin.math.roundToInt

enum class GlassVariant { REGULAR, CLEAR, RECEDED, OPAQUE }

object GlassMaterial {
    private val panels = mutableListOf<WeakReference<PanelDrawable>>()
    private val blurBindings = mutableListOf<WeakReference<BlurBinding>>()

    @Volatile var reduceTransparency: Boolean = false
        set(value) {
            field = value
            synchronized(panels) {
                panels.removeAll { it.get() == null }
                panels.forEach { it.get()?.invalidateSelf() }
            }
            if (Build.VERSION.SDK_INT >= 31) synchronized(blurBindings) {
                blurBindings.removeAll { it.get() == null }
                blurBindings.forEach { reference -> reference.get()?.let { binding ->
                    binding.window.decorView.post { binding.update() }
                } }
            }
        }

    fun panel(context: Context, variant: GlassVariant = GlassVariant.REGULAR,
        radiusDp: Float = GlassTokens.RADIUS_PANEL_DP): Drawable =
        PanelDrawable(context, variant, radiusDp).also { drawable ->
            synchronized(panels) {
                panels.removeAll { it.get() == null }
                panels.add(WeakReference(drawable))
            }
        }

    fun lens(context: Context, radiusDp: Float = GlassTokens.RADIUS_ROW_DP): Drawable =
        rounded(context, GlassTokens.lens, radiusDp).apply {
            setStroke(dp(context, 1f), 0x99FFFFFF.toInt())
        }

    fun focusable(context: Context, radiusDp: Float = GlassTokens.RADIUS_ROW_DP,
        quietFill: Boolean = true): StateListDrawable = StateListDrawable().apply {
        val idle = if (quietFill) GlassTokens.fillQuiet else Color.TRANSPARENT
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(context, idle, radiusDp))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(context, GlassTokens.textPrimary, radiusDp))
        addState(intArrayOf(android.R.attr.state_focused), lens(context, radiusDp))
        addState(intArrayOf(), rounded(context, idle, radiusDp))
    }

    fun focusText(): ColorStateList = ColorStateList(arrayOf(
        intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed),
        intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(
        GlassTokens.textSecondary, GlassTokens.lensText, GlassTokens.lensText, GlassTokens.textPrimary))

    fun applyWindowBlur(window: Window) {
        if (Build.VERSION.SDK_INT < 31) return
        synchronized(blurBindings) {
            blurBindings.removeAll { it.get() == null }
            blurBindings.firstOrNull { it.get()?.window === window }?.get()?.let {
                it.update()
                return
            }
            BlurBinding(window).also {
                blurBindings.add(WeakReference(it))
                window.decorView.addOnAttachStateChangeListener(it)
                if (window.decorView.isAttachedToWindow) it.onViewAttachedToWindow(window.decorView)
            }
        }
    }

    internal fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun rounded(context: Context, color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
    }

    @android.annotation.TargetApi(31)
    private class BlurBinding(val window: Window) : View.OnAttachStateChangeListener {
        private val manager = window.context.getSystemService(WindowManager::class.java)
        private val listener = Consumer<Boolean> { update() }
        private var listening = false

        fun update() {
            val enabled = !reduceTransparency && manager.isCrossWindowBlurEnabled
            val radius = if (enabled) dp(window.context, 28f) else 0
            window.setBackgroundBlurRadius(radius)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            var background = window.decorView.background
            if (background is InsetDrawable) background = background.drawable
            (background as? PanelDrawable)?.let { it.blurred = enabled; it.invalidateSelf() }
        }

        override fun onViewAttachedToWindow(view: View) {
            if (!listening) {
                listening = true
                manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, listener)
            }
            update()
        }

        override fun onViewDetachedFromWindow(view: View) {
            if (listening) manager.removeCrossWindowBlurEnabledListener(listener)
            listening = false
        }
    }

    private class PanelDrawable(context: Context, private val variant: GlassVariant, radiusDp: Float) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val radius = radiusDp * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var opacity = 255
        private var filter: ColorFilter? = null
        var blurred = false

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            val effective = if (reduceTransparency) GlassVariant.OPAQUE else variant
            val fill = when (effective) {
                GlassVariant.REGULAR -> if (blurred) 0xCC1C2027.toInt() else 0xE01C2027.toInt()
                // Composite 55% surface over the mandatory 35% black layer.
                GlassVariant.CLEAR -> 0xB314161B.toInt()
                GlassVariant.RECEDED -> GlassTokens.surfaceRaised
                GlassVariant.OPAQUE -> GlassTokens.surface
            }
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.colorFilter = filter
            paint.color = fill
            paint.alpha = (Color.alpha(fill) * opacity / 255)
            if (effective == GlassVariant.REGULAR && blurred) {
                // Extra tint under the sheen preserves secondary-text contrast over white.
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * .35f,
                    0xE01C2027.toInt(), fill, Shader.TileMode.CLAMP)
                paint.alpha = opacity
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (effective == GlassVariant.REGULAR || effective == GlassVariant.CLEAR) {
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * .35f,
                    0x0FFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                paint.alpha = opacity
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.shader = LinearGradient(rect.left, 0f, rect.right, 0f,
                    intArrayOf(Color.TRANSPARENT, 0x1FFFFFFF, Color.TRANSPARENT),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                val edge = minOf(radius, rect.width() / 2)
                canvas.drawLine(rect.left + edge, rect.top + 2 * density,
                    rect.right - edge, rect.top + 2 * density, paint)
            }
            paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(0x38FFFFFF, 0x1AFFFFFF, 0x0AFFFFFF), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = opacity
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            rect.inset(density / 2, density / 2)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }

        override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius) }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
        @Deprecated("Drawable opacity is not used on modern Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
