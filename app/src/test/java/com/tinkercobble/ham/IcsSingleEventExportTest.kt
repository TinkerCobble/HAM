package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.IcsEventParser
import com.tinkercobble.ham.data.IcsDayExport
import com.tinkercobble.ham.data.IcsSingleEventExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class IcsSingleEventExportTest {
    private val source = CalendarSource("export", "Export", 0, provider = CalendarProviderKind.ICS)

    @Test fun timedOccurrenceRoundTripsWithoutProviderIdentityOrAttachment() {
        val event = AgendaEvent("private-calendar", "Private", "Meeting, planning; ☕",
            "2026-11-01T09:00:00-05:00", "2026-11-01T10:00:00-05:00", false,
            "Discuss next steps\nham-attachment://12345678-1234-1234-1234-123456789abc",
            "Office, east", "server-uid", "20261101T090000", CalendarProviderKind.CALDAV,
            "FREQ=WEEKLY", true)
        val bytes = IcsSingleEventExport.render(event, UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-10-27T00:00:00Z"))
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("private-calendar"))
        assertFalse(text.contains("server-uid"))
        assertFalse(text.contains("ham-attachment://"))
        assertFalse(text.contains("RRULE"))
        assertTrue(text.contains("DTSTART:20261101T140000Z"))
        val read = IcsEventParser.parse(bytes, source).events.single()
        assertEquals(event.summary, read.summary)
        assertEquals("Discuss next steps", read.description)
        assertEquals(event.location, read.location)
    }

    @Test fun allDayAndLongUnicodeTextRoundTrip() {
        val summary = "☀️ Planning ".repeat(30).trimEnd()
        val description = "Line one\n" + "Unicode notes ☕ ".repeat(25).trimEnd()
        val event = AgendaEvent("one", "One", summary, "2026-10-27", "2026-10-29", true,
            description, "", null, null)
        val bytes = IcsSingleEventExport.render(event, UUID.randomUUID(), Instant.EPOCH)
        val physicalLines = bytes.toString(Charsets.UTF_8).removeSuffix("\r\n").split("\r\n")
        assertTrue(physicalLines.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(physicalLines.any { it.startsWith(" ") })
        assertTrue(bytes.toString(Charsets.UTF_8).contains("DTEND;VALUE=DATE:20261029"))
        val read = IcsEventParser.parse(bytes, source).events.single()
        assertEquals(summary, read.summary)
        assertEquals(description, read.description)
    }

    @Test fun twoDisplayedEventsExportAsOneCalendarWithoutSourceIdentity() {
        val first = AgendaEvent("private-a", "Private A", "First", "2026-10-29", "2026-10-30",
            true, "Visible note", "", "provider-a", null)
        val second = AgendaEvent("private-b", "Private B", "Second", "2026-10-30", "2026-10-31",
            true, "ham-attachment://12345678-1234-1234-1234-123456789abc", "", "provider-b", null)
        val bytes = IcsSingleEventExport.renderMany(listOf(first, second),
            listOf(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222")), Instant.EPOCH)
        val text = bytes.toString(Charsets.UTF_8)
        assertEquals(1, "BEGIN:VCALENDAR".toRegex().findAll(text).count())
        assertEquals(2, "BEGIN:VEVENT".toRegex().findAll(text).count())
        assertFalse(text.contains("private-a") || text.contains("private-b"))
        assertFalse(text.contains("provider-a") || text.contains("provider-b"))
        assertFalse(text.contains("ham-attachment://"))
        assertEquals(listOf("First", "Second"), IcsEventParser.parse(bytes, source).events.map { it.summary })
    }

    @Test fun dayExportKeepsBothSourceRecordsEvenWhenDisplayGroupsThem() {
        val first = AgendaEvent("calendar.synthetic", "Home Assistant", "Shared event",
            "2026-10-29", "2026-10-30", true, "First source note", "Room", "ha-uid", null,
            CalendarProviderKind.HOME_ASSISTANT)
        val second = first.copy(calendarId = "android:synthetic", calendarName = "Android",
            description = "Second source note", uid = "android-event:42", provider = CalendarProviderKind.ANDROID)
        val day = IcsDayExport.events(listOf(first, second), java.time.LocalDate.parse("2026-10-29"))
        assertEquals(2, day.size)
        val bytes = IcsSingleEventExport.renderMany(day, listOf(UUID.randomUUID(), UUID.randomUUID()),
            Instant.EPOCH)
        val notes = IcsEventParser.parse(bytes, source).events.map { it.description }
        assertEquals(setOf("First source note", "Second source note"), notes.toSet())
    }
}
