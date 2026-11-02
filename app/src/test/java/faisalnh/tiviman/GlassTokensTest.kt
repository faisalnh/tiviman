package faisalnh.tiviman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class GlassTokensTest {
    @Test fun concentricRadiiRespectMinimum() {
        assertEquals(12f, GlassTokens.innerRadius(24f, 12f), 0f)
        assertEquals(4f, GlassTokens.innerRadius(12f, 20f), 0f)
        assertEquals(20f, GlassTokens.innerRadius(28f, 8f), 0f)
    }

    @Test fun regularGlassTextPassesOverBrightestAndDarkestBackdrops() {
        for (opacity in listOf(204, 224, 255)) {
            for (backdrop in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
                val background = composite(GlassTokens.surface, opacity, backdrop)
                assertTrue(contrast(GlassTokens.textPrimary, background) >= 4.5)
                assertTrue(contrast(GlassTokens.textSecondary, background) >= 4.5)
            }
        }
    }

    @Test fun sheenPreservesSecondaryContrastAcrossBlurredPanel() {
        for (step in 0..100) {
            val fraction = step / 100.0
            val tintAlpha = (224 + (204 - 224) * fraction).toInt()
            val sheenAlpha = (15 * (1 - fraction)).toInt()
            val tint = composite(GlassTokens.surface, tintAlpha, 0xFFFFFFFF.toInt())
            val background = composite(0xFFFFFFFF.toInt(), sheenAlpha, tint)
            assertTrue(contrast(GlassTokens.textSecondary, background) >= 4.5)
        }
    }

    @Test fun clearOnlyCarriesPrimaryText() {
        val background = composite(0xFF14161B.toInt(), 179, 0xFFFFFFFF.toInt())
        assertTrue(contrast(GlassTokens.textPrimary, background) >= 4.5)
    }

    @Test fun lensAndFieldTextPassContrast() {
        for (backdrop in listOf(GlassTokens.canvas, 0xFFFFFFFF.toInt())) {
            assertTrue(contrast(GlassTokens.lensText,
                composite(GlassTokens.textPrimary, 240, backdrop)) >= 4.5)
        }
        assertTrue(contrast(GlassTokens.textSecondary, GlassTokens.surfaceRaised) >= 4.5)
        assertTrue(contrast(GlassTokens.accent, GlassTokens.surfaceRaised) >= 3)
    }

    private fun composite(color: Int, alpha: Int, background: Int): Int {
        fun channel(shift: Int): Int = (((color ushr shift and 255) * alpha +
            (background ushr shift and 255) * (255 - alpha)) / 255)
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = (color ushr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return channel(16) * .2126 + channel(8) * .7152 + channel(0) * .0722
        }
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
}
