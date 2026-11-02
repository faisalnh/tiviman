package faisalnh.tiviman

import androidx.media3.common.C
import androidx.media3.common.Tracks
import org.junit.Assert.*
import org.junit.Test

class TrackControlsTest {
    @Test fun noTracksHasHonestAutoAndOffOptions() {
        assertEquals(listOf("auto"), TrackControls.options(Tracks.EMPTY, C.TRACK_TYPE_VIDEO).map { it.key })
        assertEquals(listOf("auto", "off"), TrackControls.options(Tracks.EMPTY, C.TRACK_TYPE_TEXT).map { it.key })
    }
    @Test fun onlyImplementedTrackDestinationsAreEnabled() {
        assertEquals(setOf(5, 6, 8), TrackControls.types.keys)
        assertEquals(C.TRACK_TYPE_TEXT, TrackControls.types[8])
    }
}
