package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class RecentChannelsTest {
    @Test fun explicitTunesAreUniqueRecentAndBounded() {
        assertEquals(listOf(2, 1), RecentChannels.record(listOf(1, 2), 2))
        var ids = emptyList<Int>()
        repeat(100) { ids = RecentChannels.record(ids, it) }
        assertEquals(50, ids.size)
        assertEquals(99, ids.first())
    }
    @Test fun staleIdsAndDuplicatesAreRemoved() {
        assertEquals(listOf(2, 1), RecentChannels.restore(listOf(9, 2, 2, 1), setOf(1, 2)))
    }
}
