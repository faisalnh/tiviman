package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class SettingsMenuTest {
    @Test fun supportedRoutesAndUnavailableBackendsAreExplicit() {
        assertEquals(11, SettingsMenu.entries.count { it.available })
        assertTrue(SettingsMenu.entries.take(7).all { it.available })
        assertTrue(SettingsMenu.entries[7].available)
                assertFalse(SettingsMenu.entries[8].available)
                assertTrue(SettingsMenu.entries[8].label.contains("Unavailable"))
                assertTrue(SettingsMenu.entries.subList(9, 11).all { it.available })
        assertTrue(SettingsMenu.entries[11].available)
        assertTrue(SettingsMenu.entries[1].label.contains("this channel"))
        assertTrue(SettingsMenu.entries[5].label.contains("process session"))
    }
}
