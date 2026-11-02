package faisalnh.tiviman

/** Monotonic, session-only timer. Never relies on wall-clock time or restarts on recreation. */
class SleepTimer {
    var deadline: Long = 0
        private set
    var expired = false
        private set
    fun setMinutes(minutes: Int, now: Long) {
        require(minutes in minutesOptions)
        deadline = if (minutes == 0) 0 else now + minutes * 60_000L
        // Canceling a timer must not unexpectedly resume playback after expiry.
    }
    fun remaining(now: Long) = (deadline - now).coerceAtLeast(0)
    fun expire(now: Long): Boolean {
        if (deadline == 0L || now < deadline) return false
        deadline = 0; expired = true
        return true
    }
    fun allowPlayback() { expired = false }
    companion object {
        val minutesOptions = listOf(0, 15, 30, 45, 60, 90, 120, 180, 240)
    }
}

/** Activity-independent session state survives backgrounding/recreation, not process death/reboot. */
object PlaybackSleepTimer {
    val timer = SleepTimer()
}
