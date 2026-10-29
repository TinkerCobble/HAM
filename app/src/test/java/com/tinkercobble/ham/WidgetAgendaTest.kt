package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.WidgetAgenda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WidgetAgendaTest {
    private val day = LocalDate.parse("2026-11-04")
    private fun event(id: String, provider: CalendarProviderKind, title: String) = AgendaEvent(
        id, "Synthetic", title, "2026-11-04T09:00:00Z", "2026-11-04T10:00:00Z",
        false, "", "", null, null, provider)

    @Test fun defaultsToCountsAndRespectsSelectionAndPermission() {
        val android = event("android:4", CalendarProviderKind.ANDROID, "Private synthetic event")
        val dav = event("dav:synthetic:abc", CalendarProviderKind.CALDAV, "Selected synthetic event")
        val list = listOf(android, dav)
        val counts = WidgetAgenda.forDay(list, CalendarPreferences(), true, true,
            setOf("android:4"), setOf("dav:synthetic:abc"), emptySet(), false, day, ZoneOffset.UTC)
        assertEquals(2, counts.total)
        assertEquals(emptyList<String>(), counts.details)
        val revoked = WidgetAgenda.forDay(list, CalendarPreferences(), true, false,
            setOf("android:4"), setOf("dav:synthetic:abc"), emptySet(), true, day, ZoneOffset.UTC)
        assertEquals(1, revoked.total)
        assertFalse(revoked.details.any { it.contains("Private") })
        val hidden = WidgetAgenda.forDay(list, CalendarPreferences(hidden = setOf(dav.calendarId)),
            true, false, setOf("android:4"), setOf(dav.calendarId), emptySet(), true, day, ZoneOffset.UTC)
        assertEquals(0, hidden.total)
    }
}
