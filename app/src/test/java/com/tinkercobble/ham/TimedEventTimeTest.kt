package com.tinkercobble.ham

import com.tinkercobble.ham.data.TimedEventTime
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedEventTimeTest {
    private val zone = ZoneId.of("America/New_York")

    @Test fun normalTimeKeepsSelectedLocalDateAndZone() {
        val time = TimedEventTime.parse(LocalDate.parse("2026-10-27"), "09:00", "10:30", zone)
        assertEquals("2026-10-27T09:00-04:00[America/New_York]", time.start.toString())
        assertEquals("2026-10-27T10:30-04:00[America/New_York]", time.end.toString())
    }

    @Test fun rejectsInvalidOrderAndMalformedTime() {
        val date = LocalDate.parse("2026-10-27")
        assertTrue(runCatching { TimedEventTime.parse(date, "10:00", "09:00", zone) }.isFailure)
        assertTrue(runCatching { TimedEventTime.parse(date, "24:00", "25:00", zone) }.isFailure)
    }

    @Test fun rejectsDaylightSavingGapAndOverlap() {
        assertTrue(runCatching { TimedEventTime.parse(LocalDate.parse("2026-03-08"),
            "02:30", "03:30", zone) }.isFailure)
        assertTrue(runCatching { TimedEventTime.parse(LocalDate.parse("2026-11-01"),
            "01:30", "02:30", zone) }.isFailure)
    }
}
