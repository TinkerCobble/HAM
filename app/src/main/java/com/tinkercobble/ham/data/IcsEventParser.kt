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

enum class RecurrenceIssue(val label: String) {
    NO_RANGE("No bounded read range"),
    EXPANSION("Recurrence rule could not be expanded"),
    RANGE_OVERRIDE("This-and-future change"),
    INVALID_OVERRIDE("Changed occurrence could not be used"),
    UNMATCHED_OVERRIDE("Changed occurrence has no matching series"),
}

data class IcsRead(val events: List<AgendaEvent>, val unsupportedRecurring: Int,
    val recurrenceIssues: Map<RecurrenceIssue, Int> = emptyMap(),
    val detachedOccurrenceKeys: Set<Pair<String, String>> = emptySet())

/** Parse a bounded calendar-data property. Never silently present a recurrence master as a single event. */
object IcsEventParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    fun parse(bytes: ByteArray, source: CalendarSource, range: DateRange? = null): IcsRead {
        require(bytes.size <= MAX_BYTES) { "Calendar item is too large" }
        val calendar = CalendarBuilder().build(bytes.inputStream())
        val events = mutableListOf<AgendaEvent>()
        var recurring = 0
        val recurrenceIssues = mutableMapOf<RecurrenceIssue, Int>()
        val detachedOccurrenceKeys = mutableSetOf<Pair<String, String>>()
        fun unsupported(reason: RecurrenceIssue) {
            recurring++
            recurrenceIssues[reason] = (recurrenceIssues[reason] ?: 0) + 1
        }
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
            if (repeats && range == null) { unsupported(RecurrenceIssue.NO_RANGE); continue }
            val periods = if (repeats) try { consumed(event, startValue, range!!) }
                catch (_: Exception) { unsupported(RecurrenceIssue.EXPANSION); continue }
                else listOf(startValue to endValue)
            check(periods.size <= 2000) { "Too many event occurrences" }
            val replacementIds = mutableSetOf<String>()
            val replacements = mutableListOf<Pair<VEvent, String>>()
            for (override in matching) {
                val recurrence = override.getProperty<RecurrenceId<Temporal>>("RECURRENCE-ID")
                    .orElse(null)
                if (recurrence == null) {
                    unsupported(RecurrenceIssue.INVALID_OVERRIDE)
                    handled += override
                    continue
                }
                if (recurrence.getParameter<net.fortuna.ical4j.model.Parameter>("RANGE").isPresent) {
                    unsupported(RecurrenceIssue.RANGE_OVERRIDE)
                    handled += override
                    continue
                }
                val id = runCatching { dateText(recurrence.date) }.getOrNull()
                if (id == null || !replacementIds.add(id)) {
                    unsupported(RecurrenceIssue.INVALID_OVERRIDE)
                    handled += override
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
                if (overrideStart == null || overrideEnd == null) {
                    unsupported(RecurrenceIssue.INVALID_OVERRIDE)
                    continue
                }
                events += agenda(source, override, overrideStart, overrideEnd, id, event)
            }
            check(events.size <= 5000) { "Calendar has too many occurrences" }
        }
        // A bounded CalDAV query may return a moved occurrence without its series master.
        // Its explicit start and end are sufficient to show it, but never infer missing
        // fields or a duration from a master that was not returned.
        overrides.filter { it !in handled }.forEach { override ->
            if (cancelled(override)) return@forEach
            if (override.getProperty<RecurrenceId<Temporal>>("RECURRENCE-ID")
                    .orElse(null)?.getParameter<net.fortuna.ical4j.model.Parameter>("RANGE")
                    ?.isPresent == true) {
                unsupported(RecurrenceIssue.RANGE_OVERRIDE)
                return@forEach
            }
            val id = override.getProperty<RecurrenceId<Temporal>>("RECURRENCE-ID")
                .orElse(null)?.let { runCatching { dateText(it.date) }.getOrNull() }
            val start = override.getStartDate<Temporal>().orElse(null)?.date
            val explicitEnd = if (override.getProperty<Property>("DTEND").isPresent ||
                override.getProperty<Property>("DURATION").isPresent) {
                start?.let { end(override, it) }
            } else null
            if (id == null || start == null || explicitEnd == null) {
                unsupported(RecurrenceIssue.UNMATCHED_OVERRIDE)
            } else {
                events += agenda(source, override, start, explicitEnd, id)
                value(override, "UID")?.let { detachedOccurrenceKeys += it to id }
            }
        }
        return IcsRead(events, recurring, recurrenceIssues, detachedOccurrenceKeys)
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
