package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class ChannelBrowserTest {
    @Test fun largeCatalogReusesAllAndGroupRowsAndPreservesOrder() {
        val catalog = (0 until 100000).map { Channel(it, "Channel $it", "Group ${it % 10}", Fixtures.channels[0].programmes) }
        val state = GuideState(catalog = catalog)
        assertSame(catalog, state.browserChannels("channels", 0, emptyList(), emptySet()))
        val group = state.browserChannels("channels", 1, emptyList(), emptySet())
        assertEquals(10000, group.size)
        assertSame(group, state.browserChannels("channels", 1, emptyList(), emptySet()))
        assertEquals(listOf(99999, 2), state.browserChannels("history", 0, listOf(99999, -1, 2), emptySet()).map { it.id })
        assertEquals(listOf(2, 99999), state.browserChannels("favorites", 0, emptyList(), linkedSetOf(99999, -1, 2)).map { it.id })
    }
}
