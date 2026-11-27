package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Full, unshortened text for the unlocked notification and in-app detail. */
object PinnedReminderText {
    fun range(record: LocalReminderRecord, zone: ZoneId = ZoneId.systemDefault()): String {
        if (record.allDay) {
            val first = LocalDate.parse(record.eventStart.take(10))
            val last = runCatching { LocalDate.parse(record.details!!.end.take(10)).minusDays(1) }
                .getOrDefault(first)
            val date = first.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
            return if (last == first) "$date · All day" else
                "$date – ${last.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))} · All day"
        }
        val start = AgendaEvent.instant(record.eventStart, zone)?.atZone(zone)
            ?: return record.eventDate.toString()
        val end = record.details?.end?.let { AgendaEvent.instant(it, zone)?.atZone(zone) }
        val date = start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
        val time = start.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        return if (end == null) "$date · $time" else if (end.toLocalDate() == start.toLocalDate())
            "$date · $time – ${end.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
        else "$date · $time – ${end.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))}"
    }

    fun lines(record: LocalReminderRecord): List<String> = listOfNotNull(
        record.details?.title?.takeIf { it.isNotBlank() },
        range(record),
        record.details?.location?.takeIf { it.isNotBlank() },
    )
}
