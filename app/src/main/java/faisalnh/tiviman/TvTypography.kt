package faisalnh.tiviman

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.ceil

/** Text follows Android's sp conversion, including API 34's non-linear font scaling. */
internal object TvTypography {
    fun apply(label: TextView, size: Int) {
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
    }

    fun expansion(context: Context, scale: Float): Float = maxOf(1f,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics) / (16 * scale))

    fun lineHeight(label: TextView, lines: Int = 1): Int {
        val metrics = label.paint.fontMetricsInt
        return ceil((metrics.bottom - metrics.top + label.lineSpacingExtra) * lines.toDouble()).toInt() +
            label.compoundPaddingTop + label.compoundPaddingBottom
    }
}

internal data class GuideGeometry(val expansion: Float) {
    val identityWidth = 224
    val timelineStart = identityWidth + 2
    val timelineOrigin = 12 + timelineStart
    val rowPitch = ceil(39 * expansion.toDouble()).toInt()
    val cellHeight = rowPitch - 2
    val guideTop = 234 + ceil(64 * (expansion - 1).toDouble()).toInt()
    val visibleRows = ((540 - guideTop) / rowPitch).coerceAtLeast(1)
    fun selectedOffset(viewportHeight: Int, rowHeight: Int) =
        ((viewportHeight / rowHeight).coerceAtLeast(1) - 1) * rowHeight
}
