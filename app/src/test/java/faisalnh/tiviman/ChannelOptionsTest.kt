package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class ChannelOptionsTest {
    @Test fun resetIsChannelScopedAndDoesNotMutateInputs() {
        val tracks = mapOf("1:1" to "off", "1:2" to "hash", "1:3" to "auto", "11:1" to "other")
        val display = mapOf("1" to "REDUCED", "11" to "RATIO_4_3")
        assertEquals(mapOf("11:1" to "other"), ChannelOptions.resetTracks(tracks, 1))
        assertEquals(mapOf("11" to "RATIO_4_3"), ChannelOptions.resetDisplay(display, 1))
        assertEquals(4, tracks.size)
        assertEquals(2, display.size)
        assertEquals(tracks, ChannelOptions.resetTracks(tracks, 2))
    }

    @Test fun resetIsIdempotentWithEmptyOrDefaultChoices() {
        assertTrue(ChannelOptions.resetTracks(emptyMap(), 0).isEmpty())
        assertTrue(ChannelOptions.resetDisplay(emptyMap(), 0).isEmpty())
        val once = ChannelOptions.resetTracks(mapOf("0:1" to "auto", "2:1" to "off"), 0)
        assertEquals(once, ChannelOptions.resetTracks(once, 0))
    }
}
