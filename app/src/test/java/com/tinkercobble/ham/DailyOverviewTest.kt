package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.DailyOverview
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DailyOverviewTest {
    private fun event(id: String, provider: CalendarProviderKind, start: String, end: String,
        allDay: Boolean = false) = AgendaEvent(id, "Synthetic", "Synthetic event", start, end,
            allDay, "", "", null, null, provider)

    @Test fun countsOnlyDisplayedOccurrencesAndFindsNextUnfinishedTimedEvent() {
        val day = LocalDate.parse("2026-11-04")
        val first = event("calendar.synthetic", CalendarProviderKind.HOME_ASSISTANT,
            "2026-11-04T09:00:00Z", "2026-11-04T10:00:00Z")
        val mirror = first.copy(calendarId = "android:4", provider = CalendarProviderKind.ANDROID)
        val later = event("android:5", CalendarProviderKind.ANDROID,
            "2026-11-04T15:00:00Z", "2026-11-04T16:00:00Z")
        val allDay = event("android:6", CalendarProviderKind.ANDROID,
            "2026-11-04", "2026-11-05", true)
        val otherDay = event("android:7", CalendarProviderKind.ANDROID,
            "2026-11-05", "2026-11-06", true)
        val result = DailyOverview.forDay(listOf(first, mirror, later, allDay, otherDay),
            day, Instant.parse("2026-11-04T10:30:00Z"), ZoneOffset.UTC)
        assertEquals(3, result.total)
        assertEquals(1, result.allDay)
        assertEquals(2, result.timed)
        assertEquals(later, result.nextTimed)
    }
}
