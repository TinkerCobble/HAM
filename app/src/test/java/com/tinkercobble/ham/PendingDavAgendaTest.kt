package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSelection
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DavEventPayload
import com.tinkercobble.ham.data.DavOperation
import com.tinkercobble.ham.data.DavPendingState
import com.tinkercobble.ham.data.PendingDavAgenda
import com.tinkercobble.ham.data.PendingDavMutation
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.RepeatFrequency
import com.tinkercobble.ham.data.RepeatSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDavAgendaTest {
    private val source = CalendarSource("dav:synthetic:collection", "Synthetic", 1,
        provider = CalendarProviderKind.CALDAV)
    private val id = UUID.fromString("2db79847-3454-4a50-8912-7ee1f6486f9b")

    @Test fun queuedFiniteSeriesProjectsOnlyRequestedOccurrences() {
        val date = LocalDate.parse("2026-11-02")
        val ics = DavEventPayload.create(id, date, "Synthetic series", true,
            now = Instant.parse("2026-10-27T12:00:00Z"),
            repeat = RepeatSpec(RepeatFrequency.WEEKLY, 4))
        val change = PendingDavMutation(id, "synthetic", source.id, DavOperation.CREATE,
            null, null, ics, DavPendingState.QUEUED)
        val range = DateRange(date, date.plusDays(15))
        val drafts = PendingDavAgenda.drafts(listOf(change), listOf(source), range)
        assertEquals(listOf(date, date.plusWeeks(1), date.plusWeeks(2)),
            drafts.map { it.event.date })
        assertTrue(drafts.all { it.state == DavPendingState.QUEUED })
        assertTrue(PendingDavAgenda.drafts(listOf(change), listOf(source)).isEmpty())
    }

    @Test fun queuedDraftSurvivesProjectionAndRequiresOptInVisibility() {
        val ics = DavEventPayload.create(id, LocalDate.parse("2026-10-27"), "Synthetic pending",
            false, "09:00", "10:00", Instant.parse("2026-10-27T12:00:00Z"),
            ZoneId.of("America/New_York"))
        val change = PendingDavMutation(id, "synthetic", source.id, DavOperation.CREATE,
            null, null, ics, DavPendingState.QUEUED)
        val drafts = PendingDavAgenda.drafts(listOf(change), listOf(source))
        val display = PendingDavAgenda.merge(emptyList(), drafts)
        assertEquals(1, display.size)
        assertEquals(DavPendingState.QUEUED, PendingDavAgenda.stateFor(display.single(), drafts))
        assertTrue(CalendarSelection.visibleEvents(display, CalendarPreferences(), false, false,
            emptySet(), davVisibleIds = emptySet()).isEmpty())
        assertEquals(1, CalendarSelection.visibleEvents(display, CalendarPreferences(), false, false,
            emptySet(), davVisibleIds = setOf(source.id)).size)

        val server = display.single().copy(start = "2026-10-27T09:00-04:00",
            end = "2026-10-27T10:00-04:00", description = "Provider copy")
        assertEquals(listOf(server), PendingDavAgenda.merge(listOf(server), drafts))
        assertEquals(DavPendingState.QUEUED, PendingDavAgenda.stateFor(server, drafts))
        val changed = server.copy(summary = "Different provider event")
        assertEquals(2, PendingDavAgenda.merge(listOf(changed), drafts).size)
    }

    @Test fun malformedPayloadDoesNotEraseThePendingRecord() {
        val change = PendingDavMutation(id, "synthetic", source.id, DavOperation.CREATE,
            null, null, byteArrayOf(1, 2, 3), DavPendingState.CONFLICT)
        assertTrue(PendingDavAgenda.drafts(listOf(change), listOf(source)).isEmpty())
        // The stored queue item is kept for explicit review or discard; projection never mutates it.
        assertEquals(DavPendingState.CONFLICT, change.state)
    }

    @Test fun pendingTitleUpdateKeepsServerCopyAndShowsLabeledLocalChange() {
        val original = DavEventPayload.create(id, LocalDate.parse("2026-10-27"),
            "Original title", true, now = Instant.parse("2026-10-27T12:00:00Z"))
        val updated = DavEventPayload.updateTitle(original, "Updated locally")
        val change = PendingDavMutation(id, "synthetic", source.id, DavOperation.UPDATE,
            "https://dav.example.test/calendar/event.ics", "\"v1\"", updated,
            DavPendingState.QUEUED)
        val server = com.tinkercobble.ham.data.IcsEventParser.parse(original, source).events.single()
        val drafts = PendingDavAgenda.drafts(listOf(change), listOf(source))
        val display = PendingDavAgenda.merge(listOf(server), drafts)
        assertEquals(2, display.size)
        assertEquals("Original title", display.first().summary)
        assertEquals("Updated locally", display.last().summary)
        assertEquals(DavPendingState.QUEUED, PendingDavAgenda.stateFor(display.last(), drafts))
        assertEquals(null, PendingDavAgenda.stateFor(display.first(), drafts))
        assertTrue(PendingDavAgenda.hasPendingResource(display.first(), drafts))
        assertTrue(PendingDavAgenda.hasPendingResource(display.last(), drafts))
        assertEquals(1, PendingDavAgenda.merge(listOf(display.last()), drafts).size)
    }

    @Test fun pendingTimedMoveProjectsNewDateWithoutReplacingOldServerCopy() {
        val originalDay = LocalDate.parse("2026-11-12")
        val movedDay = originalDay.plusDays(1)
        val original = DavEventPayload.create(id, originalDay, "Synthetic move", false,
            "09:00", "10:00", Instant.parse("2026-10-27T12:00:00Z"), ZoneId.of("UTC"))
        val moved = DavEventPayload.updateTimed(original, movedDay, "Synthetic move",
            "09:00", "10:00", ZoneId.of("UTC"))
        val change = PendingDavMutation(id, "synthetic", source.id, DavOperation.UPDATE,
            "https://dav.example.test/calendar/event.ics", "\"v1\"", moved,
            DavPendingState.QUEUED, baseIcs = original)
        val drafts = PendingDavAgenda.drafts(listOf(change), listOf(source),
            DateRange(movedDay, movedDay.plusDays(1)))
        assertEquals(listOf(movedDay), drafts.map { it.event.date })
        val oldServer = com.tinkercobble.ham.data.IcsEventParser.parse(original, source).events.single()
        assertEquals(2, PendingDavAgenda.merge(listOf(oldServer), drafts).size)
        val acceptedServer = com.tinkercobble.ham.data.IcsEventParser.parse(moved, source).events.single()
        assertEquals(1, PendingDavAgenda.merge(listOf(acceptedServer), drafts).size)
    }

    @Test fun pendingAllDayMoveProjectsNewDateWithoutHidingOldServerCopy() {
        val originalDay = LocalDate.parse("2026-11-12")
        val movedDay = originalDay.plusDays(1)
        val original = DavEventPayload.create(id, originalDay, "Synthetic day move", true,
            now = Instant.parse("2026-10-27T12:00:00Z"))
        val moved = DavEventPayload.updateAllDay(original, movedDay, "Synthetic day move")
        val change = PendingDavMutation(id, "synthetic", source.id, DavOperation.UPDATE,
            "https://dav.example.test/calendar/event.ics", "\"v1\"", moved,
            DavPendingState.QUEUED, baseIcs = original)
        val drafts = PendingDavAgenda.drafts(listOf(change), listOf(source),
            DateRange(movedDay, movedDay.plusDays(1)))
        assertEquals(listOf(movedDay), drafts.map { it.event.date })
        val oldServer = com.tinkercobble.ham.data.IcsEventParser.parse(original, source).events.single()
        assertEquals(2, PendingDavAgenda.merge(listOf(oldServer), drafts).size)
        val acceptedServer = com.tinkercobble.ham.data.IcsEventParser.parse(moved, source).events.single()
        assertEquals(1, PendingDavAgenda.merge(listOf(acceptedServer), drafts).size)
    }
}
