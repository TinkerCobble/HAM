package com.tinkercobble.ham

import com.tinkercobble.ham.data.CalendarProviderKind
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.IcsEventParser
import com.tinkercobble.ham.data.IcsSingleEventImport
import java.time.LocalDate
import java.time.ZoneId
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class DavZoneAwareSeriesTest {
    @Test fun libraryVtimeZonePreservesDailyLocalTimeAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val definition = TimeZoneRegistryFactory.getInstance().createRegistry()
            .getTimeZone(zone.id)!!.vTimeZone
        val id = definition.timeZoneId!!.value
        val lines = listOf("BEGIN:VCALENDAR", "VERSION:2.0",
            definition.toString().trimEnd(), "BEGIN:VEVENT", "UID:synthetic@example.invalid",
            "DTSTAMP:20261027T120000Z", "DTSTART;TZID=$id:20261030T090000",
            "DTEND;TZID=$id:20261030T100000", "SUMMARY:Synthetic recurrence",
            "RRULE:FREQ=DAILY;COUNT=5", "END:VEVENT", "END:VCALENDAR", "")
        val bytes = lines.joinToString("\r\n").toByteArray()
        val source = CalendarSource("synthetic", "Synthetic", 0,
            provider = CalendarProviderKind.CALDAV)
        val events = IcsEventParser.parse(bytes, source,
            DateRange(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-05"))).events
        assertEquals(5, events.size)
        assertEquals(List(5) { "09:00" }, events.map {
            com.tinkercobble.ham.data.AgendaEvent.instant(it.start)!!
                .atZone(zone).toLocalTime().toString()
        })
        assertEquals("09:00", IcsSingleEventImport.preview(bytes, zone).startText)
    }
}
