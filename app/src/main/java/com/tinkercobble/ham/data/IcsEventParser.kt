package com.tinkercobble.ham.data

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.RecurrenceId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal

data class IcsRead(val events: List<AgendaEvent>, val unsupportedRecurring: Int)

/** Parse a bounded calendar-data property. Never silently present a recurrence master as a single event. */
object IcsEventParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    fun parse(bytes: ByteArray, source: CalendarSource, range: DateRange? = null): IcsRead {
        require(bytes.size <= MAX_BYTES) { "Calendar item is too large" }
        val calendar = CalendarBuilder().build(bytes.inputStream())
        val events = mutableListOf<AgendaEvent>()
        var recurring = 0
        val components = calendar.getComponents<net.fortuna.ical4j.model.component.CalendarComponent>()
            .filterIsInstance<VEvent>()
        require(components.size <= 2000) { "Calendar has too many events" }
        val overrides = components.filter { it.getProperty<Property>("RECURRENCE-ID").isPresent }
        val handled = mutableSetOf<VEvent>()
        for (event in components.filterNot { it in overrides }) {
            if (cancelled(event)) continue
            val startValue = event.getStartDate<Temporal>().orElse(null)?.date ?: continue
            val repeats = event.getProperty<Property>("RRULE").isPresent ||
                event.getProperty<Property>("RDATE").isPresent
            val endValue = end(event, startValue) ?: continue
            val uid = value(event, "UID")
            val matching = overrides.filter { uid != null && value(it, "UID") == uid }
            if (repeats && range == null) { recurring++; continue }
            val periods = if (repeats) try { consumed(event, startValue, range!!) }
                catch (_: Exception) { recurring++; continue }
                else listOf(startValue to endValue)
            check(periods.size <= 2000) { "Too many event occurrences" }
            val replacementIds = mutableSetOf<String>()
            val replacements = mutableListOf<Pair<VEvent, String>>()
            for (override in matching) {
                val recurrence = override.getProperty<RecurrenceId<Temporal>>("RECURRENCE-ID")
                    .orElse(null)
                if (recurrence == null || recurrence.getParameter<net.fortuna.ical4j.model.Parameter>("RANGE").isPresent) {
                    continue
                }
                val id = runCatching { dateText(recurrence.date) }.getOrNull()
                if (id == null || !replacementIds.add(id)) {
                    continue
                }
                replacements += override to id
                handled += override
            }
            periods.forEach { (start, end) ->
                val occurrenceId = dateText(start)
                if (occurrenceId !in replacementIds) events += agenda(source, event, start, end,
                    if (repeats) occurrenceId else null)
            }
            for ((override, id) in replacements) {
                if (cancelled(override)) continue
                val overrideStart = override.getStartDate<Temporal>().orElse(null)?.date
                val overrideEnd = overrideStart?.let { movedStart ->
                    if (override.getProperty<Property>("DTEND").isPresent ||
                        override.getProperty<Property>("DURATION").isPresent) {
                        end(override, movedStart)
                    } else {
                        inheritedEnd(startValue, endValue, movedStart)
                    }
                }
                if (overrideStart == null || overrideEnd == null) { recurring++; continue }
                events += agenda(source, override, overrideStart, overrideEnd, id, event)
            }
            check(events.size <= 5000) { "Calendar has too many occurrences" }
        }
        recurring += overrides.count { it !in handled }
        return IcsRead(events, recurring)
    }

    private fun value(event: VEvent, name: String): String? =
        event.getProperty<Property>(name).orElse(null)?.value?.ifBlank { null }

    private fun cancelled(event: VEvent) = value(event, "STATUS")?.equals("CANCELLED", ignoreCase = true) == true

    private fun end(event: VEvent, start: Temporal): Temporal? = event.getEndDate<Temporal>().orElse(null)?.date
        ?: when (start) {
            is LocalDate -> start.plusDays(1)
            is LocalDateTime -> start.plusSeconds(1)
            is ZonedDateTime -> start.plusSeconds(1)
            is OffsetDateTime -> start.plusSeconds(1)
            is Instant -> start.plusSeconds(1)
            else -> null
        }

    private fun inheritedEnd(masterStart: Temporal, masterEnd: Temporal,
        movedStart: Temporal): Temporal? = when {
        masterStart is LocalDate && masterEnd is LocalDate && movedStart is LocalDate -> {
            val days = ChronoUnit.DAYS.between(masterStart, masterEnd)
            if (days > 0) movedStart.plusDays(days) else null
        }
        masterStart !is LocalDate && masterEnd !is LocalDate && movedStart !is LocalDate ->
            runCatching {
                val length = Duration.between(masterStart, masterEnd)
                if (length.isNegative || length.isZero) null else movedStart.plus(length)
            }.getOrNull()
        else -> null
    }

    private fun agenda(source: CalendarSource, event: VEvent, start: Temporal, end: Temporal,
        recurrenceId: String?, fallback: VEvent? = null) = AgendaEvent(
        source.id, source.name, value(event, "SUMMARY") ?: fallback?.let { value(it, "SUMMARY") } ?: "Untitled event",
        dateText(start), dateText(end), start is LocalDate,
        value(event, "DESCRIPTION") ?: fallback?.let { value(it, "DESCRIPTION") } ?: "",
        value(event, "LOCATION") ?: fallback?.let { value(it, "LOCATION") } ?: "",
        value(event, "UID") ?: fallback?.let { value(it, "UID") }, recurrenceId, source.provider,
        value(event, "RRULE") ?: fallback?.let { value(it, "RRULE") }, true)

    private fun consumed(event: VEvent, start: Temporal, range: DateRange): List<Pair<Temporal, Temporal>> {
        fun <T : Temporal> expand(period: Period<T>): List<Pair<Temporal, Temporal>> =
            event.getConsumedTime(period).map { it.start to it.end }
        val first = range.start.minusDays(2)
        val last = range.endExclusive.plusDays(2)
        return when (start) {
            is LocalDate -> expand(Period(first, last))
            is LocalDateTime -> expand(Period(first.atStartOfDay(), last.atStartOfDay()))
            is ZonedDateTime -> expand(Period(first.atStartOfDay(start.zone), last.atStartOfDay(start.zone)))
            is OffsetDateTime -> expand(Period(first.atStartOfDay().atOffset(start.offset),
                last.atStartOfDay().atOffset(start.offset)))
            is Instant -> expand(Period(first.atStartOfDay(ZoneOffset.UTC).toInstant(),
                last.atStartOfDay(ZoneOffset.UTC).toInstant()))
            else -> error("Unsupported recurring calendar date")
        }
    }

    private fun dateText(value: Temporal): String = when (value) {
        is LocalDate -> value.toString()
        is ZonedDateTime -> value.toOffsetDateTime().toString()
        is OffsetDateTime -> value.toString()
        is LocalDateTime -> value.toString()
        is Instant -> value.toString()
        else -> error("Unsupported calendar date")
    }
}
