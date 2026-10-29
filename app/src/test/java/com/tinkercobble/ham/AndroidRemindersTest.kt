package com.tinkercobble.ham

import com.tinkercobble.ham.data.AndroidReminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRemindersTest {
    @Test fun acceptsBoundedMinutesWithoutInventingAnEventTime() {
        assertEquals(0, AndroidReminders.minutes("0"))
        assertEquals(60, AndroidReminders.minutes(" 60 "))
        assertEquals(10080, AndroidReminders.minutes("10080"))
        assertTrue(runCatching { AndroidReminders.minutes("-1") }.isFailure)
        assertTrue(runCatching { AndroidReminders.minutes("10081") }.isFailure)
        assertTrue(runCatching { AndroidReminders.minutes("tomorrow") }.isFailure)
    }
}
