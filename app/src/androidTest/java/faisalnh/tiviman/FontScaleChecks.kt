package faisalnh.tiviman

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Isolated layout checks: no Activity, saved catalog, provider, or global settings. */
class FontScaleChecks(private val runner: Instrumentation) {
    private fun labels(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    fun run() {
        val result = Bundle()
        try {
            runner.runOnMainSync {
                for (density in listOf(120, 160, 240, 320, 640)) {
                    var previousText = 0f
                    for (font in listOf(1f, 1.3f, 1.5f, 2f)) {
                        val config = Configuration(runner.targetContext.resources.configuration).apply {
                            densityDpi = density
                            fontScale = font
                        }
                        val context = runner.targetContext.createConfigurationContext(config)
                        val scale = density / 160f
                        val width = (960 * scale).toInt()
                        val height = (540 * scale).toInt()
                        val geometry = GuideGeometry(TvTypography.expansion(context, scale))
                        val catalog = Fixtures.channels.map { channel -> channel.copy(
                            name = "Synthetic long channel label for font scaling ${channel.id}",
                            group = "Synthetic long category label for accessibility checks",
                            programmes = channel.programmes.map { programme -> programme.copy(
                                title = "Synthetic long programme title for wrapping and font scaling checks",
                                description = "Synthetic programme description for scroll testing. ".repeat(12)) }) }
                        val state = GuideState(catalog = catalog)
                        val guide = GuideRows(context) { _, _, _ -> }
                        guide.show(state, scale, 0)
                        layout(guide, width, ((540 - geometry.guideTop) * scale).toInt())
                        val cells = labels(guide).filter { it.visibility == View.VISIBLE }
                        check(cells.isNotEmpty())
                        check(cells.first().textSize > previousText)
                        previousText = cells.first().textSize
                        cells.forEach { check(it.height >= TvTypography.lineHeight(it)) { "Clipped guide line: density=$density font=$font" } }
                        for (toolbar in listOf(false, true)) {
                            val chrome = FullscreenChrome(context)
                            chrome.show(state.copy(pane = Pane.FULLSCREEN, overlay = true, toolbar = toolbar,
                                realEpg = true), scale, false, "Synthetic playback status", "Synthetic format", true,
                                { _, _ -> }, {}, {}, "120 min", favorite = true, showSchedule = true)
                            layout(chrome, width, height)
                            check(chrome.getChildAt(0).bottom < chrome.getChildAt(1).top) { "Header overlaps shelf" }
                            labels(chrome).forEach { label ->
                                val textLayout = label.layout ?: error("Missing text layout")
                                check(label.height >= textLayout.height + label.compoundPaddingTop + label.compoundPaddingBottom) {
                                    "Clipped caption: ${label.text}; density=$density font=$font"
                                }
                                check((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                            }
                        }
                    }
                }
            }
            result.putString("fontScale", "PASS: native sp growth, guide line fit, fullscreen wrapped captions and non-overlap; five densities, 100/130/150/200%")
            runner.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("fontScale", error.stackTraceToString())
            runner.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
