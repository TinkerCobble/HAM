package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarReadError
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarReadErrorTest {
    @Test fun preservesOnlyBoundedErrorCategories() {
        assertEquals(CalendarReadError.AUTH,
            CalendarReadError.from("Authorization expired for a private calendar"))
        assertEquals(CalendarReadError.PERMISSION,
            CalendarReadError.from("Insufficient calendar permission"))
        assertEquals(CalendarReadError.UNSUPPORTED,
            CalendarReadError.from("Unsupported recurrence"))
        assertEquals(CalendarReadError.PARTIAL,
            CalendarReadError.from("Some CalDAV items could not be read"))
        assertEquals(CalendarReadError.MISSING_DATA,
            CalendarReadError.from("Some CalDAV item data was missing"))
        assertEquals(CalendarReadError.UNPARSEABLE_DATA,
            CalendarReadError.from("Some CalDAV item data could not be parsed"))
        assertEquals(CalendarReadError.UNVERIFIED_METADATA,
            CalendarReadError.from("Some CalDAV item metadata could not be verified"))
        assertEquals(CalendarReadError.UNAVAILABLE,
            CalendarReadError.from("Network unavailable"))
        assertEquals(CalendarReadError.OTHER,
            CalendarReadError.from("An unexpected provider response"))
    }
}
