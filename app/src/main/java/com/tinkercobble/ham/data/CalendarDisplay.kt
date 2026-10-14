package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CalendarView(val label: String, val days: Long) {
    MONTH("Month", 0), WEEK("Next 7 days", 7), THREE_DAYS("3 days", 3);

    fun range(anchor: LocalDate): DateRange = when (this) {
        MONTH -> DateRange(anchor.withDayOfMonth(1), anchor.withDayOfMonth(1).plusMonths(1))
        else -> DateRange(anchor, anchor.plusDays(days))
    }

    fun move(anchor: LocalDate, direction: Long): LocalDate = when (this) {
        MONTH -> anchor.plusMonths(direction)
        else -> anchor.plusDays(direction * days)
    }
}

data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    val days: List<LocalDate> get() = (0 until java.time.temporal.ChronoUnit.DAYS.between(start, endExclusive))
        .map { start.plusDays(it) }
    fun contains(day: LocalDate) = !day.isBefore(start) && day.isBefore(endExclusive)
}

object CalendarDisplay {
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

    fun time(value: String, zone: ZoneId = ZoneId.systemDefault()): String =
        AgendaEvent.instant(value, zone)?.atZone(zone)?.format(timeFormat) ?: value

    fun dateTime(value: String, zone: ZoneId = ZoneId.systemDefault()): String =
        AgendaEvent.instant(value, zone)?.atZone(zone)?.format(dateTimeFormat) ?: value

    fun onDay(events: List<AgendaEvent>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AgendaEvent> =
        events.filter { it.occursOn(day, zone) }.sortedWith(compareBy<AgendaEvent>(
            { if (it.allDay) 0 else 1 },
            { if (it.allDay) null else AgendaEvent.instant(it.start, zone) },
            { it.calendarName }, { it.summary }, { it.identity }
        ))

    fun titleForDay(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
    }
}
