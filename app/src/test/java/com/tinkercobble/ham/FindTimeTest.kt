package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.FindTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class FindTimeTest {
    private val day = LocalDate.parse("2026-11-05")
    private val zone = ZoneId.of("America/New_York")
    private fun event(start: String, end: String, allDay: Boolean = false) =
        AgendaEvent("synthetic", "Synthetic", "Busy", start, end, allDay,
            "", "", null, null)

    @Test fun excludesBusyTimeAndTravelBuffers() {
        val busy = event("2026-11-05T10:00:00-05:00", "2026-11-05T11:00:00-05:00")
        val slots = FindTime.suggest(day, listOf(busy), LocalTime.of(9, 0),
            LocalTime.of(12, 0), 30, 15, zone)
        assertEquals(listOf("09:00", "09:15", "11:15", "11:30"),
            slots.map { it.start.toLocalTime().toString() })
    }

    @Test fun allDayAndCrossMidnightEventsBlockOnlyTheirOverlap() {
        assertTrue(FindTime.suggest(day, listOf(event("2026-11-05", "2026-11-06", true)),
            LocalTime.of(9, 0), LocalTime.of(17, 0), 60, 0, zone).isEmpty())
        val overnight = event("2026-11-04T23:30:00-05:00", "2026-11-05T09:30:00-05:00")
        val slots = FindTime.suggest(day, listOf(overnight), LocalTime.of(9, 0),
            LocalTime.of(11, 0), 30, 0, zone)
        assertEquals("09:30", slots.first().start.toLocalTime().toString())
    }

    @Test fun refusesAmbiguousDaylightSavingSlotAndInvalidInputs() {
        val fall = LocalDate.parse("2026-11-01")
        val slots = FindTime.suggest(fall, emptyList(), LocalTime.of(1, 0),
            LocalTime.of(3, 0), 30, 0, zone)
        assertTrue(slots.none { it.start.hour == 1 })
        assertTrue(runCatching { FindTime.suggest(day, emptyList(), LocalTime.of(17, 0),
            LocalTime.of(9, 0), 30, 0, zone) }.isFailure)
        assertTrue(runCatching { FindTime.suggest(day, emptyList(), LocalTime.of(9, 0),
            LocalTime.of(17, 0), 25, 0, zone) }.isFailure)
    }
}
