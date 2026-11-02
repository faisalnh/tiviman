package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class QuickChannelsTest {
    @Test fun hiddenFullscreenMovesWithinGroupWithoutWrapping() {
        val state = GuideState(pane = Pane.FULLSCREEN, group = 1)
        val next = state.move(RemoteKey.RIGHT)
        assertEquals(3, next.tunedId)
        assertEquals(3, next.channelId)
        assertEquals(0, state.move(RemoteKey.LEFT).tunedId)
        assertEquals(9, next.move(RemoteKey.RIGHT).move(RemoteKey.RIGHT).move(RemoteKey.RIGHT).tunedId)
        assertEquals(0, state.copy(overlay = true).move(RemoteKey.RIGHT).tunedId)
    }

    @Test fun favoritesAreARealGroupWithCatalogOrderAndEmptySafety() {
        val initial = GuideState(favorites = setOf(6, 1, 4))
        val favorite = initial.selectGroup(initial.favoritesGroup).copy(pane = Pane.FULLSCREEN, tunedId = 1)
        assertEquals(listOf(1, 4, 6), favorite.channels.map { it.id })
        assertEquals(4, favorite.move(RemoteKey.RIGHT).tunedId)
        assertEquals(6, favorite.move(RemoteKey.RIGHT).move(RemoteKey.RIGHT).tunedId)
        val empty = favorite.copy(favorites = emptySet())
        assertEquals(empty, empty.move(RemoteKey.RIGHT))
        assertEquals(empty.copy(pane = Pane.GUIDE), empty.copy(pane = Pane.GUIDE).move(RemoteKey.DOWN))
        assertEquals(empty.copy(pane = Pane.GUIDE), empty.copy(pane = Pane.GUIDE).move(RemoteKey.CENTER))
    }

    @Test fun bookmarksRestoreFavoritesIncludingEmptyGroup() {
        val initial = GuideState()
        val bookmark = NavigationBookmark(initial.favoritesGroup, initial.favoritesGroup, 4, 0, 4)
        val restored = bookmark.restore(Fixtures.channels, setOf(1, 4))
        assertEquals(listOf(1, 4), restored.channels.map { it.id })
        assertEquals(initial.favoritesGroup, restored.group)
        assertEquals(4, restored.channelId)
        assertTrue(bookmark.restore(Fixtures.channels).channels.isEmpty())
        assertEquals(Fixtures.groups, initial.groups.dropLast(1))
    }

    @Test fun removedTunedFavoriteUsesRemainingRowsWithoutStoppingOrWrapping() {
        val initial = GuideState(favorites = setOf(1, 4, 6))
        val removed = initial.selectGroup(initial.favoritesGroup)
            .copy(pane = Pane.FULLSCREEN, tunedId = 4, favorites = setOf(1, 6))
        assertEquals(4, removed.tunedId)
        assertTrue(removed.move(RemoteKey.RIGHT).tunedId in setOf(1, 6))
        assertTrue(removed.move(RemoteKey.LEFT).tunedId in setOf(1, 6))
        val empty = removed.copy(favorites = emptySet())
        assertEquals(empty, empty.move(RemoteKey.LEFT))
        assertEquals(empty, empty.move(RemoteKey.RIGHT))
    }
}
