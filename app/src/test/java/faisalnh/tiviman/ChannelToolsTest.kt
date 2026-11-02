package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class ChannelToolsTest {
    @Test fun searchIsTrimmedCaseInsensitiveNameOnlyAndBounded() {
        assertEquals(listOf(Fixtures.channels[0]), ChannelTools.search(Fixtures.channels, "  HORIZON "))
        assertTrue(ChannelTools.search(Fixtures.channels, " ").isEmpty())
        assertTrue(ChannelTools.search(Fixtures.channels, "asset:///").isEmpty())
        assertEquals(2, ChannelTools.search(Fixtures.channels, "a", 2).size)
        assertTrue(ChannelTools.search(Fixtures.channels, ".*").isEmpty())
    }
    @Test fun favoritesToggleDoesNotMutateOriginal() {
        val original = setOf(1)
        assertEquals(setOf(1, 2), ChannelTools.toggle(original, 2))
        assertEquals(emptySet<Int>(), ChannelTools.toggle(original, 1))
        assertEquals(setOf(1), original)
    }
}
