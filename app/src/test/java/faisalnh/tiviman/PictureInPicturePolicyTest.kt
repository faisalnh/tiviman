package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class PictureInPicturePolicyTest {
    @Test fun capabilityRequiresPlatformAndFeature() {
        assertFalse(PictureInPicturePolicy.available(25, true))
        assertFalse(PictureInPicturePolicy.available(34, false))
        assertTrue(PictureInPicturePolicy.available(26, true))
    }

    @Test fun entryRequiresAnActiveNonFailedSession() {
        assertTrue(PictureInPicturePolicy.canEnter(true, true, false, true, false, false))
        assertFalse(PictureInPicturePolicy.canEnter(false, true, false, true, false, false))
        assertFalse(PictureInPicturePolicy.canEnter(true, false, false, true, false, false))
        assertFalse(PictureInPicturePolicy.canEnter(true, true, true, true, false, false))
        assertFalse(PictureInPicturePolicy.canEnter(true, true, false, false, false, false))
        assertFalse(PictureInPicturePolicy.canEnter(true, true, false, true, true, false))
        assertFalse(PictureInPicturePolicy.canEnter(true, true, false, true, false, true))
    }

    @Test fun settingsOnlyEnablesPipOnSupportedDevices() {
        assertFalse(SettingsMenu.forDevice(false)[8].available)
        assertTrue(SettingsMenu.forDevice(true)[8].available)
        assertEquals(SettingsMenu.entries.filterIndexed { index, _ -> index != 8 },
            SettingsMenu.forDevice(true).filterIndexed { index, _ -> index != 8 })
    }
}
