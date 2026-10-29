package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.LocalReminderPlanner
import com.tinkercobble.ham.data.LocalReminderWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LocalReminderPlanTest {
    private val zone = ZoneId.of("America/New_York")

    private fun event(start: String = "2026-11-01T10:00:00-05:00",
        allDay: Boolean = false, uid: String? = "fixture-uid",
        source: String = "ics:fixture") = AgendaEvent(
        source, "Fixture", "Synthetic event", start,
        if (allDay) "2026-11-02" else "2026-11-01T11:00:00-05:00",
        allDay, "", "", uid, null, CalendarProviderKind.ICS)

    @Test fun timedOffsetUsesKnownInstant() {
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"),
            LocalReminderPlanner.plan(event(), LocalReminderWhen.MINUTES_BEFORE, 60, zone).triggerAt)
    }

    @Test fun allDayMorningDoesNotMoveEventStart() {
        val dateOnly = event(start = "2026-11-01", allDay = true)
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"),
            LocalReminderPlanner.plan(dateOnly, LocalReminderWhen.MORNING_OF, zone = zone).triggerAt)
        assertEquals("2026-11-01", dateOnly.start)
        assertThrows(IllegalArgumentException::class.java) {
            LocalReminderPlanner.plan(dateOnly, LocalReminderWhen.MINUTES_BEFORE, 60, zone)
        }
    }

    @Test fun previousEveningUsesLocalWallClockAcrossDst() {
        assertEquals(Instant.parse("2026-10-31T22:00:00Z"),
            LocalReminderPlanner.plan(event(), LocalReminderWhen.PREVIOUS_EVENING, zone = zone).triggerAt)
    }

    @Test fun dateReminderRetimesAcrossTimeZonesWithoutChangingAllDayDate() {
        val start = "2026-11-01"
        val east = LocalReminderPlanner.trigger(start, true, LocalReminderWhen.MORNING_OF,
            zone = ZoneId.of("America/New_York"))
        val west = LocalReminderPlanner.trigger(start, true, LocalReminderWhen.MORNING_OF,
            zone = ZoneId.of("America/Los_Angeles"))
        assertEquals(east.first, west.first)
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), east.second)
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), west.second)
    }

    @Test fun timedMorningUsesEventDateInCurrentTimeZone() {
        val start = "2026-11-01T05:00:00Z"
        val east = LocalReminderPlanner.trigger(start, false, LocalReminderWhen.MORNING_OF,
            zone = ZoneId.of("America/New_York"))
        val west = LocalReminderPlanner.trigger(start, false, LocalReminderWhen.MORNING_OF,
            zone = ZoneId.of("America/Los_Angeles"))
        assertEquals("2026-11-01", east.first.toString())
        assertEquals("2026-10-31", west.first.toString())
    }

    @Test fun stableUidSurvivesTitleAndTimeRefreshButSourceRemainsDistinct() {
        val first = LocalReminderPlanner.plan(event(), LocalReminderWhen.MORNING_OF, zone = zone)
        val moved = LocalReminderPlanner.plan(event(start = "2026-11-02T10:00:00-05:00")
            .copy(summary = "Changed title"), LocalReminderWhen.MORNING_OF, zone = zone)
        assertEquals(first.eventKey, moved.eventKey)
        assertNotEquals(first.triggerAt, moved.triggerAt)
        assertNotEquals(first.eventKey, LocalReminderPlanner.plan(event(source = "ics:other"),
            LocalReminderWhen.MORNING_OF, zone = zone).eventKey)
    }

    @Test fun sameUidAcrossSourcesProducesDuplicateHintOnly() {
        val first = LocalReminderPlanner.plan(event(), LocalReminderWhen.MORNING_OF, zone = zone)
        val second = LocalReminderPlanner.plan(event(source = "ics:other"),
            LocalReminderWhen.MORNING_OF, zone = zone)
        assertNotEquals(first.eventKey, second.eventKey)
        assertEquals(first.duplicateKey, second.duplicateKey)
    }

    @Test fun noUidFallsBackToExactEventIdentity() {
        val first = LocalReminderPlanner.plan(event(uid = null), LocalReminderWhen.MORNING_OF, zone = zone)
        val second = LocalReminderPlanner.plan(event(uid = null).copy(summary = "Changed"),
            LocalReminderWhen.MORNING_OF, zone = zone)
        assertNotEquals(first.eventKey, second.eventKey)
    }
}
