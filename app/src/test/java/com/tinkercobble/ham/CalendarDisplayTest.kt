package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarDisplay
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.RefreshGeneration
import com.tinkercobble.ham.data.mergeCalendarRead
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarDisplayTest {
    private val zone = ZoneId.of("America/New_York")
    private fun event(id: String, start: String, end: String, allDay: Boolean = false) =
        AgendaEvent(id, id, "Event", start, end, allDay, "", "", null, null)

    @Test fun rollingRangesAndSwipesKeepTodayAsAnchor() {
        val today = LocalDate.of(2026, 10, 14)
        assertEquals(today to today.plusDays(7), CalendarView.WEEK.range(today).let { it.start to it.endExclusive })
        assertEquals(today to today.plusDays(3), CalendarView.THREE_DAYS.range(today).let { it.start to it.endExclusive })
        assertEquals(today.plusDays(7), CalendarView.WEEK.move(today, 1))
        assertEquals(today.minusDays(3), CalendarView.THREE_DAYS.move(today, -1))
        assertEquals(today.plusMonths(1), CalendarView.MONTH.move(today, 1))
        assertEquals(LocalDate.of(2026, 10, 1), CalendarView.MONTH.range(today).start)
        assertEquals(LocalDate.of(2026, 11, 1), CalendarView.MONTH.range(today).endExclusive)
        assertEquals(29, CalendarView.MONTH.range(LocalDate.of(2028, 2, 15)).days.size)
        assertEquals(28, CalendarView.MONTH.range(LocalDate.of(2027, 2, 15)).days.size)
    }

    @Test fun localAmPmAndDstUseInstantsNotStringSlices() {
        assertEquals("12:00 AM", CalendarDisplay.time("2026-10-14T04:00:00Z", zone))
        assertEquals("12:00 PM", CalendarDisplay.time("2026-10-14T16:00:00Z", zone))
        assertEquals("1:30 AM", CalendarDisplay.time("2026-11-01T05:30:00Z", zone))
        assertEquals("1:30 AM", CalendarDisplay.time("2026-11-01T06:30:00Z", zone))
        val spanning = event("calendar.a", "2026-03-08T01:30:00-05:00", "2026-03-08T03:30:00-04:00")
        assertTrue(spanning.occursOn(LocalDate.of(2026, 3, 8), zone))
        assertFalse(spanning.occursOn(LocalDate.of(2026, 3, 9), zone))
    }

    @Test fun allDayAndTimedMultiDayEventsAppearOnEveryApplicableDay() {
        val allDay = event("calendar.a", "2026-10-14", "2026-10-17", true)
        val overnight = event("calendar.b", "2026-10-14T23:30:00-04:00", "2026-10-15T00:30:00-04:00")
        assertTrue(allDay.occursOn(LocalDate.of(2026, 10, 16), zone))
        assertFalse(allDay.occursOn(LocalDate.of(2026, 10, 17), zone))
        assertEquals(listOf(allDay, overnight), CalendarDisplay.onDay(listOf(overnight, allDay), LocalDate.of(2026, 10, 14), zone))
        assertEquals(2, CalendarDisplay.onDay(listOf(allDay, overnight), LocalDate.of(2026, 10, 15), zone).size)
    }

    @Test fun visibilityColorsAndViewPersistWithoutChangingCalendarIdentity() {
        val a = CalendarSource("calendar.one", "Scouts", 1)
        val b = CalendarSource("calendar.two", "Scouts", 0)
        val prefs = CalendarPreferences(defaultId = a.id).hideAll(listOf(a, b))
            .setColor(b.id, "#123ABC").copy(view = CalendarView.THREE_DAYS)
        assertEquals(setOf(a.id, b.id), prefs.hidden)
        val restored = CalendarPreferences.fromStored(prefs.favorites, prefs.hidden, prefs.defaultId,
            prefs.recentString(), prefs.colorString(), prefs.view.name)
        assertEquals(prefs, restored)
        assertEquals(a.id, restored.defaultId)
        assertTrue(restored.showAll(listOf(a, b)).hidden.isEmpty())
        assertEquals(setOf("calendar.temporarily_unavailable"),
            restored.copy(hidden = restored.hidden + "calendar.temporarily_unavailable").showAll(listOf(a, b)).hidden)
        assertEquals("#123ABC", restored.colors[b.id])
        assertEquals(CalendarView.WEEK, CalendarPreferences.fromStored(emptySet(), emptySet(), null, null).view)
    }

    @Test fun failedSourceRetainsItsPriorEventsButNewerRangeWins() {
        val day = LocalDate.of(2026, 10, 14)
        val old = event("calendar.failed", "2026-10-14", "2026-10-15", true)
        val fresh = event("calendar.ok", "2026-10-14", "2026-10-15", true)
        val range = DateRange(day, day.plusDays(1))
        assertEquals(setOf(old.calendarId, fresh.calendarId),
            mergeCalendarRead(listOf(old), listOf(fresh), setOf(old.calendarId), range).map { it.calendarId }.toSet())
        assertTrue(mergeCalendarRead(listOf(old), emptyList(), setOf(old.calendarId),
            DateRange(day.plusDays(2), day.plusDays(3))).isEmpty())
        val generation = RefreshGeneration()
        val first = generation.next()
        val second = generation.next()
        assertFalse(generation.accepts(first))
        assertTrue(generation.accepts(second))
    }
}
