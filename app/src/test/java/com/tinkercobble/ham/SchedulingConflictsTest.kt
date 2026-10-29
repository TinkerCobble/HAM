package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.ConflictKind
import com.tinkercobble.ham.data.SchedulingConflicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SchedulingConflictsTest {
    private val day = LocalDate.parse("2026-11-05")
    private val zone = ZoneId.of("America/New_York")
    private fun event(id: String, title: String, start: String, end: String,
        provider: CalendarProviderKind = CalendarProviderKind.ANDROID,
        allDay: Boolean = false) = AgendaEvent(id, id, title, start, end,
        allDay, "", "", id, null, provider)

    @Test fun exactMirrorDoesNotConflictWithItselfButCanOverlapAnotherEvent() {
        val android = event("android:1", "Planning", "2026-11-05T09:00:00-05:00",
            "2026-11-05T10:00:00-05:00")
        val ha = event("calendar.planning", "Planning", "2026-11-05T14:00:00Z",
            "2026-11-05T15:00:00Z", CalendarProviderKind.HOME_ASSISTANT)
        assertTrue(SchedulingConflicts.detect(day, listOf(android, ha), 15, zone).isEmpty())
        val other = event("android:2", "Review", "2026-11-05T09:30:00-05:00",
            "2026-11-05T10:30:00-05:00")
        val conflict = SchedulingConflicts.detect(day, listOf(android, ha, other), 15, zone).single()
        assertEquals(2, conflict.first.size)
        assertEquals(ConflictKind.OVERLAP, conflict.kind)
    }

    @Test fun distinguishesOverlapFromTravelGapAndBlocksAllDay() {
        val first = event("one", "First", "2026-11-05T09:00:00-05:00",
            "2026-11-05T10:00:00-05:00")
        val near = event("two", "Near", "2026-11-05T10:10:00-05:00",
            "2026-11-05T11:00:00-05:00")
        assertTrue(SchedulingConflicts.detect(day, listOf(first, near), 0, zone).isEmpty())
        assertEquals(ConflictKind.TRAVEL_BUFFER,
            SchedulingConflicts.detect(day, listOf(first, near), 15, zone).single().kind)
        val allDay = event("three", "All day", "2026-11-05", "2026-11-06",
            allDay = true)
        assertEquals(ConflictKind.OVERLAP,
            SchedulingConflicts.detect(day, listOf(first, allDay), 0, zone).single().kind)
    }
}
