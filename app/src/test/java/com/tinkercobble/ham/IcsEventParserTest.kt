package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.IcsEventParser
import com.tinkercobble.ham.data.DateRange
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.component.VEvent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class IcsEventParserTest {
    private val source = CalendarSource("synthetic", "Synthetic", 0)
    @Test fun readsDatesAndTimesButDoesNotMisrepresentRecurrenceMasters() {
        val document = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:day@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
SUMMARY:Sample day
END:VEVENT
BEGIN:VEVENT
UID:time@example.test
DTSTAMP:20261027T120000Z
DTSTART:20261101T140000Z
DTEND:20261101T150000Z
SUMMARY:Sample time
END:VEVENT
BEGIN:VEVENT
UID:repeat@example.test
DTSTAMP:20261027T120000Z
DTSTART:20261101T160000Z
DTEND:20261101T170000Z
RRULE:FREQ=WEEKLY;COUNT=3
SUMMARY:Repeating
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(document.toByteArray(), source)
        assertEquals(2, read.events.size)
        assertEquals(1, read.unsupportedRecurring)
        assertEquals("2026-11-01", read.events[0].start)
        assertEquals("2026-11-01T14:00Z", read.events[1].start)
    }

    @Test fun refusesOversizedCalendarItems() {
        assertEquals(true, runCatching { IcsEventParser.parse(ByteArray(512 * 1024 + 1), source) }.isFailure)
    }

    @Test fun libraryExpandsBoundedAllDayRecurrence() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:repeat@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
RRULE:FREQ=WEEKLY;COUNT=3
SUMMARY:Repeat
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val calendar = CalendarBuilder().build(ics.byteInputStream())
        val event = calendar.getComponents<VEvent>().single()
        val periods = event.getConsumedTime(Period(LocalDate.parse("2026-11-01"),
            LocalDate.parse("2026-12-01")))
        assertEquals(3, periods.size)
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01")))
        assertEquals(3, read.events.size)
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(listOf("2026-11-01", "2026-11-08", "2026-11-15"), read.events.map { it.start })
    }

    @Test fun expandsUtcTimedRecurrenceOnlyInsideRange() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:timed-repeat@example.test
DTSTAMP:20261027T120000Z
DTSTART:20261101T140000Z
DTEND:20261101T150000Z
RRULE:FREQ=DAILY;COUNT=3
SUMMARY:Timed repeat
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04")))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(listOf("2026-11-02T14:00Z", "2026-11-03T14:00Z"),
            read.events.filter { it.occursOn(LocalDate.parse("2026-11-02")) ||
                it.occursOn(LocalDate.parse("2026-11-03")) }.map { it.start })
    }

    @Test fun recurrenceExceptionMovesOnlyItsOwnOccurrence() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:changed@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
RRULE:FREQ=WEEKLY;COUNT=2
SUMMARY:Original
END:VEVENT
BEGIN:VEVENT
UID:changed@example.test
DTSTAMP:20261027T120000Z
RECURRENCE-ID;VALUE=DATE:20261108
DTSTART;VALUE=DATE:20261109
DTEND;VALUE=DATE:20261110
SUMMARY:Moved
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-15")))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(listOf("2026-11-01", "2026-11-09"), read.events.map { it.start })
        assertEquals("2026-11-08", read.events.last().recurrenceId)
    }

    @Test fun cancelledOverrideRemovesOnlyItsOccurrence() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:cancel@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
RRULE:FREQ=WEEKLY;COUNT=2
SUMMARY:Original
END:VEVENT
BEGIN:VEVENT
UID:cancel@example.test
DTSTAMP:20261027T120000Z
RECURRENCE-ID;VALUE=DATE:20261108
STATUS:CANCELLED
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-15")))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(listOf("2026-11-01"), read.events.map { it.start })
    }

    @Test fun movedTimedOverrideWithoutEndInheritsMasterDuration() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:duration@example.test
DTSTAMP:20261027T120000Z
DTSTART:20261101T140000Z
DTEND:20261101T153000Z
RRULE:FREQ=DAILY;COUNT=2
SUMMARY:Original
END:VEVENT
BEGIN:VEVENT
UID:duration@example.test
DTSTAMP:20261027T120000Z
RECURRENCE-ID:20261102T140000Z
DTSTART:20261102T180000Z
SUMMARY:Moved
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04")))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals(listOf("2026-11-01T14:00Z", "2026-11-02T18:00Z"),
            read.events.map { it.start })
        assertEquals("2026-11-02T19:30Z", read.events.last().end)
    }

    @Test fun movedMultiDayOverrideWithoutEndInheritsMasterDuration() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:long@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261103
RRULE:FREQ=WEEKLY;COUNT=2
SUMMARY:Two days
END:VEVENT
BEGIN:VEVENT
UID:long@example.test
DTSTAMP:20261027T120000Z
RECURRENCE-ID;VALUE=DATE:20261108
DTSTART;VALUE=DATE:20261110
SUMMARY:Moved two days
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-15")))
        assertEquals(0, read.unsupportedRecurring)
        assertEquals("2026-11-10", read.events.last().start)
        assertEquals("2026-11-12", read.events.last().end)
    }

    @Test fun thisAndFutureOverrideReportsUnsupportedRange() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:range@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
RRULE:FREQ=WEEKLY;COUNT=3
SUMMARY:Original
END:VEVENT
BEGIN:VEVENT
UID:range@example.test
DTSTAMP:20261027T120000Z
RECURRENCE-ID;VALUE=DATE;RANGE=THISANDFUTURE:20261108
DTSTART;VALUE=DATE:20261109
DTEND;VALUE=DATE:20261110
SUMMARY:Moved future
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val read = IcsEventParser.parse(ics.toByteArray(), source,
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01")))
        assertEquals(1, read.unsupportedRecurring)
    }
}
