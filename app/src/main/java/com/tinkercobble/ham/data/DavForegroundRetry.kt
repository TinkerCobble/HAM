package com.tinkercobble.ham.data

/** Rate limit automatic attempts while HAM is foregrounded; manual retry bypasses it. */
object DavForegroundRetry {
    const val INTERVAL_MILLIS = 5 * 60 * 1000L
    fun due(lastAttemptElapsed: Long, nowElapsed: Long): Boolean =
        lastAttemptElapsed == 0L || nowElapsed < lastAttemptElapsed ||
            nowElapsed - lastAttemptElapsed >= INTERVAL_MILLIS
}
