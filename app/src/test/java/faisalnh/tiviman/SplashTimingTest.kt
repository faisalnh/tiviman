package faisalnh.tiviman

import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTimingTest {
    @Test fun fastLoadWaitsForMinimum() {
        assertEquals(600L, remainingSplashDelay(1000, 1000))
        assertEquals(400L, remainingSplashDelay(1000, 1200))
    }

    @Test fun slowLoadDoesNotWait() {
        assertEquals(0L, remainingSplashDelay(1000, 1600))
        assertEquals(0L, remainingSplashDelay(1000, 4000))
    }

    @Test fun clockBeforeStartDoesNotExtendMinimum() {
        assertEquals(600L, remainingSplashDelay(1000, 900))
    }
}
