package faisalnh.tiviman

internal fun remainingSplashDelay(start: Long, now: Long, minimum: Long = 600L): Long =
    (minimum - (now - start).coerceAtLeast(0L)).coerceAtLeast(0L)
