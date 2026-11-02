package faisalnh.tiviman

import android.view.Gravity
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object DisplaySizing {
    fun apply(video: PlayerView, mode: DisplayMode, width: Int, height: Int) {
        val size = mode.size(width, height)
        val current = video.layoutParams as? FrameLayout.LayoutParams
        if (current == null || current.width != size.width || current.height != size.height || current.gravity != Gravity.CENTER) {
            video.layoutParams = FrameLayout.LayoutParams(size.width, size.height, Gravity.CENTER)
        }
        val resizeMode = when (mode) {
            DisplayMode.FULL, DisplayMode.RATIO_4_3, DisplayMode.RATIO_16_9, DisplayMode.RATIO_235 ->
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            DisplayMode.ZOOM, DisplayMode.CINEMA_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        if (video.resizeMode != resizeMode) video.resizeMode = resizeMode
    }
}
