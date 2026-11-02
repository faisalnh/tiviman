package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class NavigationBookmarkTest {
    @Test fun restoresDistinctGroupFocusAndTune() {
        val restored = NavigationBookmark(2, 3, 4, 1, 0).restore(Fixtures.channels)
        assertEquals(2, restored.group)
        assertEquals(3, restored.focusedGroup)
        assertEquals(4, restored.channelId)
        assertEquals(1, restored.programmeIndex)
        assertEquals(0, restored.tunedId)
    }
    @Test fun staleOrInvalidIndicesFallBackSafely() {
        val restored = NavigationBookmark(9999, 9999, -10, 9999, -1).restore(Fixtures.channels)
        assertEquals(0, restored.group)
        assertTrue(restored.channels.any { it.id == restored.channelId })
        assertEquals(restored.channel.programmes.lastIndex, restored.programmeIndex)
        assertEquals(0, restored.tunedId)
    }
}
