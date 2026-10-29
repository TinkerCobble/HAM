package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A standalone copy of one displayed occurrence. It never exposes provider IDs or HAM attachments. */
object IcsSingleEventExport {
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun render(event: AgendaEvent, exportId: UUID, now: java.time.Instant): ByteArray {
        return renderMany(listOf(event), listOf(exportId), now)
    }

    /** A bounded snapshot of displayed occurrences, with fresh UIDs and no provider ownership. */
    fun renderMany(events: List<AgendaEvent>, exportIds: List<UUID>, now: java.time.Instant): ByteArray {
        require(events.size in 1..50 && exportIds.size == events.size) { "Choose 1 to 50 events" }
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//TinkerCobble//HAM//EN",
            "CALSCALE:GREGORIAN")
        events.forEachIndexed { index, event -> lines += eventLines(event, exportIds[index], now) }
        lines += "END:VCALENDAR"
        return (lines.joinToString("\r\n") { IcsContentLines.fold(it) } + "\r\n").toByteArray(Charsets.UTF_8)
            .also { require(it.size <= IcsEventParser.MAX_BYTES) { "Events are too large to export" } }
    }

    private fun eventLines(event: AgendaEvent, exportId: UUID, now: java.time.Instant): List<String> {
        require(event.summary.isNotBlank()) { "Event has no title" }
        val start = if (event.allDay) LocalDate.parse(event.start.take(10)) else null
        val end = if (event.allDay) LocalDate.parse(event.end.take(10)) else null
        if (start != null && end != null) require(end.isAfter(start)) { "Invalid event end" }
        if (start == null) require(
            (AgendaEvent.instant(event.end) ?: error("Invalid event end"))
                .isAfter(AgendaEvent.instant(event.start) ?: error("Invalid event start"))
        ) { "Invalid event end" }
        val startLine = if (start != null) "DTSTART;VALUE=DATE:${start.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            else "DTSTART:${utc.format(AgendaEvent.instant(event.start) ?: error("Invalid event start"))}"
        val endLine = if (end != null) "DTEND;VALUE=DATE:${end.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            else "DTEND:${utc.format(AgendaEvent.instant(event.end) ?: error("Invalid event end"))}"
        val description = event.description.lines().filterNot { it.contains("ham-attachment://") }
            .joinToString("\n").trim()
        val lines = mutableListOf(
            "BEGIN:VEVENT", "UID:$exportId@ham-export.local",
            "DTSTAMP:${utc.format(now)}", startLine, endLine,
            "SUMMARY:${escape(event.summary)}",
        )
        if (description.isNotEmpty()) lines += "DESCRIPTION:${escape(description)}"
        if (event.location.isNotBlank()) lines += "LOCATION:${escape(event.location)}"
        lines += "END:VEVENT"
        return lines
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\")
        .replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
}
