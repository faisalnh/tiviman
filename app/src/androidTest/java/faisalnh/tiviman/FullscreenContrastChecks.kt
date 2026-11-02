package faisalnh.tiviman

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.pow

/** Renders synthetic state only; no Activity, saved catalog, or playback is opened. */
class FullscreenContrastChecks(private val runner: Instrumentation) {
    private fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }

    private fun labels(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    fun run() {
        val result = Bundle()
        try {
            runner.runOnMainSync {
                for (fontScale in listOf(1f, 2f)) for (scale in listOf(0.75f, 1f, 2f)) {
                    for (toolbar in listOf(false, true)) for (realEpg in listOf(false, true)) {
                        val config = android.content.res.Configuration(runner.targetContext.resources.configuration).apply {
                            this.fontScale = fontScale
                            densityDpi = (160 * scale).toInt()
                        }
                        val chrome = FullscreenChrome(runner.targetContext.createConfigurationContext(config))
                        chrome.show(GuideState(pane = Pane.FULLSCREEN, overlay = true,
                            toolbar = toolbar, realEpg = realEpg), scale, false,
                            "Synthetic status", "Synthetic format", true,
                            { _, _ -> }, {}, {}, "Off", showSchedule = true)
                        val width = (960 * scale).toInt()
                        val height = (540 * scale).toInt()
                        chrome.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        chrome.layout(0, 0, width, height)
                        if (toolbar) {
                            val capsule = FullscreenChrome::class.java.getDeclaredField("toolbarCapsule").let {
                                it.isAccessible = true
                                it.get(chrome) as ViewGroup
                            }
                            val scroll = capsule.getChildAt(0) as android.widget.HorizontalScrollView
                            check(scroll.isSmoothScrollingEnabled) { "Toolbar scrolling must remain smooth" }
                            check(!scroll.isHorizontalFadingEdgeEnabled &&
                                scroll.overScrollMode == View.OVER_SCROLL_NEVER) {
                                "Glass toolbar must not draw opaque fading edges or overscroll effects"
                            }
                        }
                        val text = labels(chrome)
                        check(text.size >= 2)
                        val colors = text.associateWith { it.currentTextColor }
                        // Remove glyphs, not their bounds, so every pixel beneath each label is checked.
                        text.forEach { it.setTextColor(Color.TRANSPARENT) }
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            for (video in listOf(Color.WHITE, Color.BLACK)) {
                                bitmap.eraseColor(video)
                                chrome.draw(Canvas(bitmap))
                                val clearY = (chrome.getChildAt(0).bottom + chrome.getChildAt(1).top) / 2
                                check(bitmap.getPixel(width / 2, clearY) == video) {
                                    "Central video must remain unobscured"
                                }
                                for (label in text) {
                                    val area = Rect(0, 0, label.width, label.height)
                                    chrome.offsetDescendantRectToMyCoords(label, area)
                                    var ancestor = label.parent
                                    while (ancestor is View && ancestor !== chrome) {
                                        val clip = Rect(0, 0, ancestor.width, ancestor.height)
                                        chrome.offsetDescendantRectToMyCoords(ancestor, clip)
                                        if (!area.intersect(clip)) { area.setEmpty(); break }
                                        ancestor = ancestor.parent
                                    }
                                    if (area.isEmpty || !area.intersect(0, 0, width, height)) continue
                                    val foreground = colors.getValue(label)
                                    check(Color.alpha(foreground) == 255)
                                    val foregroundLuminance = luminance(foreground)
                                    for (y in area.top until area.bottom) for (x in area.left until area.right) {
                                        val backgroundLuminance = luminance(bitmap.getPixel(x, y))
                                        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
                                            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
                                        check(ratio >= 4.5) {
                                            "Contrast $ratio at $x,$y; toolbar=$toolbar realEpg=$realEpg scale=$scale"
                                        }
                                    }
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            result.putString("fullscreenContrast", "PASS: full visible label bounds over white/black, both panels, EPG modes, three densities, 100/200% fonts; central video clear")
            runner.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("fullscreenContrast", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            runner.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
