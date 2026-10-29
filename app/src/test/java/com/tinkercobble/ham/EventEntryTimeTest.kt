package com.tinkercobble.ham

import com.tinkercobble.ham.data.EventEntryTime
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class EventEntryTimeTest {
    @Test fun shorthandAndStandardTime() {
        listOf("330p", "3:30p", "3:30 PM", "15:30", "1530").forEach {
            assertEquals(it, LocalTime.of(15, 30), EventEntryTime.parse(it))
        }
        assertEquals(LocalTime.of(3, 0), EventEntryTime.parse("3 AM"))
        assertNull(EventEntryTime.parse("25:30"))
        assertNull(EventEntryTime.parse("3:75p"))
    }

    @Test fun suggestedDurationNeverWrapsSameDay() {
        assertEquals(LocalTime.of(16, 30), EventEntryTime.suggestedEnd(LocalTime.of(15, 30)))
        assertEquals(LocalTime.of(15, 45), EventEntryTime.suggestedEnd(LocalTime.of(15, 30), 15))
        assertNull(EventEntryTime.suggestedEnd(LocalTime.of(23, 30)))
    }
}
