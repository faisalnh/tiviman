package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class GuideGeometryTest {
    @Test fun channelIdentityAndRulerShareTimelineOrigin() {
        for (font in listOf(1f, 1.3f, 2f)) {
            val geometry = GuideGeometry(font)
            assertEquals(224, geometry.identityWidth)
            assertEquals(geometry.identityWidth + 2, geometry.timelineStart)
            assertEquals(12 + geometry.timelineStart, geometry.timelineOrigin)
        }
    }
    @Test fun largerFontsTradeRowsForReadableCells() {
        val normal = GuideGeometry(1f)
        val large = GuideGeometry(2f)
        assertEquals(39, normal.rowPitch)
        assertEquals(78, large.rowPitch)
        assertTrue(large.guideTop > normal.guideTop)
        assertTrue(large.visibleRows < normal.visibleRows)
        assertTrue(large.visibleRows >= 2)
    }

    @Test fun scrollOffsetFitsWholeRowsAcrossDensitiesAndFonts() {
        for (density in listOf(0.75f, 1f, 1.5f, 2f, 4f)) {
            for (font in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) {
                val geometry = GuideGeometry(font)
                val viewport = ((540 - geometry.guideTop) * density).toInt()
                val row = (geometry.rowPitch * density).toInt()
                val offset = geometry.selectedOffset(viewport, row)
                assertTrue(offset >= 0)
                assertTrue(offset + row <= viewport)
                assertEquals(0, offset % row)
            }
        }
    }
}
