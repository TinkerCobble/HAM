package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.IcsEventParser
import com.tinkercobble.ham.data.IcsSingleEventExport
import com.tinkercobble.ham.data.IcsSingleEventImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class IcsSingleEventImportTest {
    private val zone = ZoneId.of("America/New_York")

    @Test fun exportedAllDayEventPreviewsWithoutSourceIdentity() {
        val event = AgendaEvent("private", "Private", "Synthetic appointment",
            "2026-11-05", "2026-11-06", true, "Notes", "Library", "old-uid", null)
        val bytes = IcsSingleEventExport.render(event, UUID.randomUUID(), Instant.EPOCH)
        val draft = IcsSingleEventImport.preview(bytes, zone)
        assertEquals("Synthetic appointment", draft.title)
        assertEquals("2026-11-05", draft.date.toString())
        assertEquals("Notes", draft.description)
        assertEquals("Library", draft.location)
        assertTrue(draft.allDay)
    }

    @Test fun exportedMultiDayEventPreviewsExclusiveEndWithoutSourceIdentity() {
        val event = AgendaEvent("private", "Private", "Synthetic trip",
            "2026-11-05", "2026-11-09", true, "Notes", "Library", "old-uid", null)
        val bytes = IcsSingleEventExport.render(event, UUID.randomUUID(), Instant.EPOCH)
        val draft = IcsSingleEventImport.preview(bytes, zone)
        assertEquals("2026-11-05", draft.date.toString())
        assertEquals("2026-11-09", draft.endExclusive.toString())
        assertTrue(draft.allDay)
        assertTrue(!bytes.toString(Charsets.UTF_8).contains("old-uid"))
        val invalid = bytes.toString(Charsets.UTF_8).replace("DTEND;VALUE=DATE:20261109",
            "DTEND;VALUE=DATE:20261105")
        assertTrue(runCatching { IcsSingleEventImport.preview(invalid.toByteArray(), zone) }.isFailure)
    }

    @Test fun timedEventUsesExactLocalClockAndRejectsLostSeconds() {
        fun file(start: String, end: String) = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:synthetic@example.invalid
            DTSTAMP:20261027T000000Z
            DTSTART:$start
            DTEND:$end
            SUMMARY:Synthetic meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n").toByteArray()
        val draft = IcsSingleEventImport.preview(file("20261105T140000Z", "20261105T150000Z"), zone)
        assertEquals("09:00", draft.startText)
        assertEquals("10:00", draft.endText)
        assertTrue(runCatching { IcsSingleEventImport.preview(
            file("20261105T140030Z", "20261105T150000Z"), zone) }.isFailure)
    }

    @Test fun boundedAllDaySeriesImportsAsOneReviewedDraft() {
        val recurring = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:synthetic@example.invalid
            DTSTAMP:20261027T000000Z
            DTSTART;VALUE=DATE:20261105
            DTEND;VALUE=DATE:20261106
            RRULE:FREQ=DAILY;COUNT=2
            SUMMARY:Synthetic repeat
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().toByteArray()
        val draft = IcsSingleEventImport.preview(recurring, zone)
        assertEquals("2026-11-05", draft.date.toString())
        assertEquals("FREQ=DAILY;COUNT=2", draft.repeat?.rrule)
        val reordered = recurring.toString(Charsets.UTF_8).replace(
            "RRULE:FREQ=DAILY;COUNT=2", "RRULE:COUNT=3;FREQ=WEEKLY")
        assertEquals("FREQ=WEEKLY;COUNT=3",
            IcsSingleEventImport.preview(reordered.toByteArray(), zone).repeat?.rrule)
        for (rule in listOf("FREQ=DAILY;COUNT=53", "FREQ=DAILY;COUNT=1",
            "FREQ=DAILY;COUNT=2;BYDAY=TH", "FREQ=YEARLY;COUNT=2")) {
            val invalid = recurring.toString(Charsets.UTF_8).replace("FREQ=DAILY;COUNT=2", rule)
            assertTrue(runCatching { IcsSingleEventImport.preview(invalid.toByteArray(), zone) }.isFailure)
        }
        val exception = recurring.toString(Charsets.UTF_8).replace(
            "SUMMARY:Synthetic repeat", "EXDATE;VALUE=DATE:20261106\nSUMMARY:Synthetic repeat")
        assertTrue(runCatching { IcsSingleEventImport.preview(exception.toByteArray(), zone) }.isFailure)
        val timed = recurring.toString(Charsets.UTF_8).replace("DTSTART;VALUE=DATE:20261105",
            "DTSTART:20261105T140000Z").replace("DTEND;VALUE=DATE:20261106",
            "DTEND:20261105T150000Z")
        val timedDraft = IcsSingleEventImport.preview(timed.toByteArray(), zone)
        assertEquals(false, timedDraft.allDay)
        assertEquals("09:00", timedDraft.startText)
        assertEquals("10:00", timedDraft.endText)
        assertEquals(zone.id, timedDraft.deviceZoneId)
        assertEquals("FREQ=DAILY;COUNT=2", timedDraft.repeat?.rrule)
    }

    @Test fun timedSeriesRefusesUtcClockShiftAndFloatingTime() {
        fun file(start: String, end: String) = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:synthetic@example.invalid
            DTSTAMP:20261027T000000Z
            DTSTART:$start
            DTEND:$end
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Synthetic meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().toByteArray()
        assertTrue(runCatching { IcsSingleEventImport.preview(
            file("20261030T130000Z", "20261030T140000Z"), zone) }.isFailure)
        assertTrue(runCatching { IcsSingleEventImport.preview(
            file("20261105T090000", "20261105T100000"), zone) }.isFailure)
        val stable = IcsSingleEventImport.preview(
            file("20261105T140000Z", "20261105T150000Z"), zone)
        assertEquals("09:00", stable.startText)
        assertEquals(5, stable.repeat?.count)
    }

    @Test fun zonedTimedSeriesKeepsLocalClockAcrossDaylightSavingChange() {
        val file = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:America/New_York
            BEGIN:DAYLIGHT
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0400
            TZNAME:EDT
            DTSTART:19700308T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:-0400
            TZOFFSETTO:-0500
            TZNAME:EST
            DTSTART:19701101T020000
            RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:synthetic@example.invalid
            DTSTAMP:20261027T000000Z
            DTSTART;TZID=America/New_York:20261030T090000
            DTEND;TZID=America/New_York:20261030T100000
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Synthetic local meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().toByteArray()
        val draft = IcsSingleEventImport.preview(file, zone)
        assertEquals("09:00", draft.startText)
        assertEquals("10:00", draft.endText)
        assertEquals(5, draft.repeat?.count)
    }

    @Test fun multiEventAndOversizedInputStayBounded() {
        val recurring = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:synthetic@example.invalid
            DTSTAMP:20261027T000000Z
            DTSTART;VALUE=DATE:20261105
            DTEND;VALUE=DATE:20261106
            RRULE:FREQ=DAILY;COUNT=2
            SUMMARY:Synthetic repeat
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().toByteArray()
        val mixed = recurring.toString(Charsets.UTF_8)
            .replace("END:VCALENDAR", "BEGIN:VEVENT\nUID:second@example.invalid\nDTSTAMP:20261027T000000Z\nDTSTART;VALUE=DATE:20261107\nDTEND;VALUE=DATE:20261108\nSUMMARY:Second\nEND:VEVENT\nEND:VCALENDAR")
        assertTrue(runCatching { IcsSingleEventImport.previewAll(mixed.toByteArray(), zone) }.isFailure)
        val twoEvents = recurring.toString(Charsets.UTF_8).replace("RRULE:FREQ=DAILY;COUNT=2\n", "")
            .replace("END:VCALENDAR", "BEGIN:VEVENT\nUID:second@example.invalid\nDTSTAMP:20261027T000000Z\nDTSTART;VALUE=DATE:20261107\nDTEND;VALUE=DATE:20261108\nSUMMARY:Second\nEND:VEVENT\nEND:VCALENDAR")
        assertTrue(runCatching { IcsSingleEventImport.preview(twoEvents.toByteArray(), zone) }.isFailure)
        val drafts = IcsSingleEventImport.previewAll(twoEvents.toByteArray(), zone)
        assertEquals(2, drafts.size)
        assertEquals("Synthetic repeat", drafts[0].title)
        assertEquals("Second", drafts[1].title)
        val duplicate = twoEvents.replace("20261107", "20261105").replace("20261108", "20261106")
            .replace("SUMMARY:Second", "SUMMARY:Synthetic repeat")
        assertTrue(runCatching { IcsSingleEventImport.previewAll(duplicate.toByteArray(), zone) }.isFailure)
        assertTrue(runCatching { IcsSingleEventImport.readLimited(ByteArrayInputStream(
            ByteArray(IcsEventParser.MAX_BYTES + 1))) }.isFailure)
    }
}
