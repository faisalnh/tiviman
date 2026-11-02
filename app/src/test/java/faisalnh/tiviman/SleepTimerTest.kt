package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun observedChoicesAndDeadline() {
        assertEquals(listOf(0, 15, 30, 45, 60, 90, 120, 180, 240), SleepTimer.minutesOptions)
        val timer = SleepTimer()
        timer.setMinutes(15, 1000)
        assertEquals(901000, timer.deadline)
        assertFalse(timer.expire(900999))
        assertTrue(timer.expire(901000))
        assertTrue(timer.expired)
        assertFalse(timer.expire(901001))
        assertEquals(0, timer.remaining(901001))
    }
    @Test fun cancelReplaceAndExplicitResume() {
        val timer = SleepTimer()
        timer.setMinutes(15, 0)
        timer.setMinutes(30, 100)
        assertEquals(1800100, timer.deadline)
        timer.setMinutes(0, 200)
        assertFalse(timer.expire(Long.MAX_VALUE))
        timer.setMinutes(15, 1000)
        timer.expire(901000)
        timer.setMinutes(0, 901001)
        assertTrue(timer.expired)
        timer.allowPlayback()
        assertFalse(timer.expired)
    }
    @Test fun overdueBackgroundSessionExpiresWithoutExtendingDeadline() {
        val timer = SleepTimer()
        timer.setMinutes(15, 1000)
        val deadline = timer.deadline
        // No callbacks while the player is stopped/backgrounded; next start checks elapsed time.
        assertEquals(deadline, timer.deadline)
        assertTrue(timer.expire(deadline + 120000))
        assertTrue(timer.expired)
        assertEquals(0L, timer.deadline)
    }
}
