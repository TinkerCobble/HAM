package com.tinkercobble.ham

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.IcsEventParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IcsEventParserInstrumentedTest {
    @Test fun parsesARealIcsDocumentOnAndroidRuntime() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:synthetic@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
SUMMARY:Synthetic day
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val parsed = IcsEventParser.parse(ics.toByteArray(), CalendarSource("synthetic", "Synthetic", 0))
        assertEquals(1, parsed.events.size)
        assertEquals("2026-11-01", parsed.events.single().start)
    }

    @Test fun expandsSyntheticRecurrenceOnAndroidRuntime() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//HAM Test//EN
BEGIN:VEVENT
UID:repeat@example.test
DTSTAMP:20261027T120000Z
DTSTART;VALUE=DATE:20261101
DTEND;VALUE=DATE:20261102
RRULE:FREQ=WEEKLY;COUNT=2
SUMMARY:Repeat
END:VEVENT
END:VCALENDAR
""".trimIndent().replace("\n", "\r\n")
        val parsed = IcsEventParser.parse(ics.toByteArray(), CalendarSource("synthetic", "Synthetic", 0),
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-16")))
        assertEquals(2, parsed.events.size)
        assertEquals(0, parsed.unsupportedRecurring)
    }

    @Test fun movedExceptionKeepsMasterDurationOnAndroidRuntime() {
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
        val parsed = IcsEventParser.parse(ics.toByteArray(),
            CalendarSource("synthetic", "Synthetic", 0),
            DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-04")))
        assertEquals(0, parsed.unsupportedRecurring)
        assertEquals("2026-11-02T19:30Z", parsed.events.last().end)
    }
}
