package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class GuideStateTest {
    @Test fun focusDoesNotTuneAndCenterIsTwoStep() {
        val focused = GuideState().move(RemoteKey.DOWN)
        assertEquals(0, focused.tunedId)
        assertEquals(1, focused.channelId)
        val tuned = focused.move(RemoteKey.CENTER)
        assertEquals(Pane.GUIDE, tuned.pane)
        assertEquals(1, tuned.tunedId)
        assertEquals(Pane.FULLSCREEN, tuned.move(RemoteKey.CENTER).pane)
    }
    @Test fun futureProgrammeDoesNotTune() {
        val future = GuideState().move(RemoteKey.DOWN).move(RemoteKey.RIGHT)
        assertEquals(future, future.move(RemoteKey.CENTER))
        assertEquals(0, future.tunedId)
    }
    @Test fun programmeAvailabilityUsesTimeRatherThanCellIndex() {
        val future = Programme("Future", 30, 30, "Synthetic future information")
        assertFalse(future.isCurrentAt(20))
        assertEquals("Future programme. Actions unavailable in this prototype.", future.unavailableActionExplanation(20))
        assertTrue(future.isCurrentAt(30))
        assertNull(future.unavailableActionExplanation(30))
        assertFalse(future.isCurrentAt(60))
        assertEquals("Past programme. Playback unavailable in this prototype.", future.unavailableActionExplanation(60))
    }
    @Test fun futureAtFirstIndexCannotTuneOrOpenFullscreen() {
        val rows = Fixtures.channels.map { it.copy(programmes = listOf(Programme("Future", 30, 30, "Synthetic"))) }
        for (id in listOf(0, 1)) {
            val state = GuideState(catalog = rows, channelId = id)
            assertFalse(state.canActivateProgramme)
            assertEquals(state, state.move(RemoteKey.CENTER))
        }
    }
    @Test fun currentAtLaterIndexRetainsTwoStepTune() {
        val state = GuideState(channelId = 1, programmeIndex = 1, nowMinute = 45)
        assertTrue(state.canActivateProgramme)
        val tuned = state.move(RemoteKey.CENTER)
        assertEquals(1, tuned.tunedId)
        assertEquals(Pane.GUIDE, tuned.pane)
        assertEquals(Pane.FULLSCREEN, tuned.move(RemoteKey.CENTER).pane)
    }
    @Test fun expiredAndEmptyGuideCannotActivate() {
        val expired = GuideState(nowMinute = 500)
        assertFalse(expired.canActivateProgramme)
        assertEquals(expired, expired.move(RemoteKey.CENTER))
        val empty = GuideState().let { it.copy(group = it.favoritesGroup) }
        assertFalse(empty.canActivateProgramme)
        assertEquals(empty, empty.move(RemoteKey.CENTER))
    }
    @Test fun groupSelectionKeepsTunedChannel() {
        val selected = GuideState().move(RemoteKey.LEFT).move(RemoteKey.DOWN)
            .move(RemoteKey.DOWN).move(RemoteKey.CENTER)
        assertEquals(2, selected.group)
        assertEquals(1, selected.channelId)
        assertEquals(0, selected.tunedId)
    }
    @Test fun backDismissesOverlayThenRestoresGuideFocus() {
        val guide = GuideState().move(RemoteKey.DOWN).move(RemoteKey.CENTER)
        val fullscreen = guide.move(RemoteKey.CENTER)
        val info = fullscreen.move(RemoteKey.CENTER)
        assertTrue(info.overlay)
        assertEquals(fullscreen, info.move(RemoteKey.BACK))
        assertEquals(guide, info.move(RemoteKey.BACK).move(RemoteKey.BACK))
    }
    @Test fun boundariesAndMissingEpgAreSafe() {
        var state = GuideState()
        repeat(30) { state = state.move(RemoteKey.DOWN) }
        assertEquals(11, state.channelId)
        repeat(30) { state = state.move(RemoteKey.RIGHT) }
        assertEquals(3, state.programmeIndex)
        val missing = GuideState(channelId = 7).move(RemoteKey.RIGHT)
        assertEquals(0, missing.programmeIndex)
    }
    @Test fun longBackReturnsToPlayer() {
        assertEquals(Pane.FULLSCREEN, GuideState().move(RemoteKey.LONG_BACK).pane)
    }
    @Test fun panelToolbarTransitionsRememberFocusWithoutTuning() {
        val panel = GuideState().move(RemoteKey.CENTER).move(RemoteKey.CENTER)
        var toolbar = panel.move(RemoteKey.DOWN)
        assertTrue(toolbar.toolbar)
        repeat(30) { toolbar = toolbar.move(RemoteKey.RIGHT) }
        assertEquals(13, toolbar.toolbarAction)
        val returned = toolbar.move(RemoteKey.UP)
        assertFalse(returned.toolbar)
        assertTrue(returned.overlay)
        assertEquals(13, returned.move(RemoteKey.DOWN).toolbarAction)
        assertEquals(0, returned.tunedId)
        assertEquals(0, returned.channelId)
        assertFalse(toolbar.move(RemoteKey.BACK).overlay)
        assertFalse(toolbar.move(RemoteKey.BACK).toolbar)
    }
    @Test fun unavailableActionsAreNoOpsAndHiddenDirectionsDoNotChangeFocus() {
        val full = GuideState(pane = Pane.FULLSCREEN)
        assertEquals(1, full.move(RemoteKey.RIGHT).tunedId)
        assertEquals(full, full.move(RemoteKey.DOWN))
        val history = full.move(RemoteKey.CENTER).move(RemoteKey.RIGHT)
        assertEquals(history, history.move(RemoteKey.CENTER))
        var toolbar = history.move(RemoteKey.DOWN)
        PlayerToolbar.entries.forEachIndexed { index, _ ->
            assertEquals(index, toolbar.toolbarAction)
            assertEquals(toolbar, toolbar.move(RemoteKey.CENTER))
            toolbar = toolbar.move(RemoteKey.RIGHT)
        }
        repeat(30) { toolbar = toolbar.move(RemoteKey.LEFT) }
        assertEquals(0, toolbar.toolbarAction)
    }
}
