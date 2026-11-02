package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class DisplayModeTest {
    @Test fun referenceOrderAndSafeFallback() {
        assertEquals(listOf("Normal", "Full", "Zoom", "Zoom - 2.35:1", "Reduced", "4:3", "16:9", "2.35:1"),
            DisplayMode.entries.map { it.label })
        assertEquals(DisplayMode.NORMAL, DisplayMode.parse("bad"))
        assertEquals(DisplayMode.ZOOM, DisplayMode.parse("ZOOM"))
    }
    @Test fun fixedRatiosFitInsideWideAndTallViewports() {
        for ((width, height) in listOf(1920 to 1080, 1024 to 768, 1080 to 1920, 320 to 180)) {
            for (mode in DisplayMode.entries) {
                val size = mode.size(width, height)
                assertTrue(size.width in 1..width && size.height in 1..height)
                if (mode.ratio > 0) assertEquals(mode.ratio, size.width.toFloat() / size.height, 0.015f)
            }
        }
        assertEquals(DisplayMode.Size(1440, 1080), DisplayMode.RATIO_4_3.size(1920, 1080))
        assertEquals(DisplayMode.Size(1536, 864), DisplayMode.REDUCED.size(1920, 1080))
        assertEquals(DisplayMode.Size(0, 0), DisplayMode.NORMAL.size(0, 0))
    }
}
