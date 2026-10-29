package com.tinkercobble.ham

import com.tinkercobble.ham.data.DavForegroundRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavForegroundRetryTest {
    @Test fun automaticRetryWaitsFiveMinutesButResetsAfterElapsedClockRestart() {
        val last = 100_000L
        assertFalse(DavForegroundRetry.due(last, last + 4 * 60_000L))
        assertTrue(DavForegroundRetry.due(last, last + 5 * 60_000L))
        assertTrue(DavForegroundRetry.due(last, 5_000L))
        assertTrue(DavForegroundRetry.due(0L, 5_000L))
    }
}
