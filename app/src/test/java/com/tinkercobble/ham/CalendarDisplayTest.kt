package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarDisplay
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.EventGroups
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.RefreshGeneration
import com.tinkercobble.ham.data.mergeCalendarRead
import com.tinkercobble.ham.data.mergeCachedCalendarRead
import com.tinkercobble.ham.data.pruneRemovedCalendars
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarDisplayTest {
    @Test fun exactCrossProviderMirrorsStayAccessibleAsTwoRecords() {
        val ha = event("calendar.alpha", "2026-10-27T10:00:00-04:00", "2026-10-27T11:00:00-04:00")
            .copy(summary = "Shared event", description = "Same notes", location = "Room")
        val android = ha.copy(calendarId = "android:23", calendarName = "Device copy",
            start = "2026-10-27T14:00:00Z", end = "2026-10-27T15:00:00Z",
            provider = CalendarProviderKind.ANDROID)
        assertEquals(listOf(listOf(ha, android)), EventGroups.display(listOf(ha, android)))
        assertEquals(1, EventGroups.display(listOf(ha, android.copy(description = "Different notes"))).size)
        assertEquals(2, EventGroups.display(listOf(ha, android.copy(location = "Different room"))).size)
        assertEquals(2, EventGroups.display(listOf(ha, ha.copy(calendarId = "calendar.beta"))).size)
        assertEquals(3, EventGroups.display(listOf(ha, android, android.copy(calendarId = "android:24"))).size)
        val dav = android.copy(calendarId = "dav:synthetic", provider = CalendarProviderKind.CALDAV)
        assertEquals(2, EventGroups.display(listOf(ha, dav)).size)
        assertEquals(2, EventGroups.display(listOf(android, dav)).size)
    }

    @Test fun removedAndroidCalendarClearsOnlyItsOwnChoices() {
        val prefs = CalendarPreferences(defaultId = "android:23")
        val (clean, selected) = CalendarSelection.pruneMissingAndroidChoices(prefs,
            setOf("android:23", "android:24"), setOf("android:24"))
        assertEquals(null, clean.defaultId)
        assertEquals(setOf("android:24"), selected)
        val ha = CalendarPreferences(defaultId = "calendar.family")
        assertEquals("calendar.family", CalendarSelection.pruneMissingAndroidChoices(ha,
            setOf("android:23"), emptySet()).first.defaultId)
    }

    private val zone = ZoneId.of("America/New_York")
    private fun event(id: String, start: String, end: String, allDay: Boolean = false) =
        AgendaEvent(id, id, "Event", start, end, allDay, "", "", null, null)

    @Test fun searchNeverRevealsDeselectedOrRevokedAndroidEvents() {
        val ha = event("calendar.shared", "2026-10-27", "2026-10-28", true)
        val android = event("android:23", "2026-10-27", "2026-10-28", true)
            .copy(provider = CalendarProviderKind.ANDROID)
        val hiddenHa = event("calendar.hidden", "2026-10-27", "2026-10-28", true)
        val events = listOf(ha, android, hiddenHa)
        val prefs = CalendarPreferences(hidden = setOf(hiddenHa.calendarId))
        fun shown(enabled: Boolean, granted: Boolean, ids: Set<String>, includeHidden: Boolean) =
            CalendarSelection.visibleEvents(events, prefs, enabled, granted, ids, includeHidden)
        assertEquals(listOf(ha), shown(true, true, emptySet(), false))
        assertEquals(listOf(ha, hiddenHa), shown(true, true, emptySet(), true))
        assertEquals(listOf(ha, android), shown(true, true, setOf(android.calendarId), false))
        assertEquals(listOf(ha), shown(true, false, setOf(android.calendarId), false))
        assertEquals(listOf(ha, hiddenHa), shown(true, false, setOf(android.calendarId), true))
        assertEquals(listOf(ha), shown(false, true, setOf(android.calendarId), false))
    }

    @Test fun cachedDavEventsRequireAnExplicitSelectedCollection() {
        val dav = event("dav:00000000-0000-0000-0000-000000000000:123456789012345678901234",
            "2026-10-27", "2026-10-28", true).copy(provider = CalendarProviderKind.CALDAV)
        val hidden = CalendarSelection.visibleEvents(listOf(dav), CalendarPreferences(),
            true, true, emptySet(), includeHidden = true)
        assertTrue(hidden.isEmpty())
        val selected = CalendarSelection.visibleEvents(listOf(dav), CalendarPreferences(),
            true, true, emptySet(), davVisibleIds = setOf(dav.calendarId))
        assertEquals(listOf(dav), selected)
    }

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

    @Test fun cachedRefreshReplacesOnlySuccessfulSourceAndRequestedDates() {
        val day = LocalDate.of(2026, 10, 14)
        val range = DateRange(day, day.plusDays(1))
        val old = event("calendar.ok", "2026-10-14", "2026-10-15", true)
        val elsewhere = event("calendar.ok", "2026-10-18", "2026-10-19", true)
        val failed = event("calendar.failed", "2026-10-14", "2026-10-15", true)
        val fresh = event("calendar.ok", "2026-10-14T12:00:00Z", "2026-10-14T13:00:00Z")
        val result = mergeCachedCalendarRead(listOf(old, elsewhere, failed), listOf(fresh),
            setOf("calendar.ok"), range)
        assertEquals(setOf(elsewhere, failed, fresh), result.toSet())
        assertEquals(setOf(elsewhere, failed), mergeCachedCalendarRead(result, emptyList(),
            setOf("calendar.ok"), range).toSet())
    }

    @Test fun confirmedCalendarDeletionPrunesOnlyThatProvidersCachedEvents() {
        val removed = event("calendar.removed", "2026-10-14", "2026-10-15", true)
        val retained = event("calendar.kept", "2026-10-14", "2026-10-15", true)
        val android = event("android:1", "2026-10-14", "2026-10-15", true)
            .copy(provider = CalendarProviderKind.ANDROID)
        assertEquals(listOf(retained, android), pruneRemovedCalendars(
            listOf(removed, retained, android), CalendarProviderKind.HOME_ASSISTANT,
            setOf("calendar.kept")))
    }
}
