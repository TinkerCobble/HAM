package com.tinkercobble.ham.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ConflictKind { OVERLAP, TRAVEL_BUFFER }

data class SchedulingConflict(val first: List<AgendaEvent>, val second: List<AgendaEvent>,
    val kind: ConflictKind)

/** Advisory local conflicts. One exact HA/Android mirror is one displayed event. */
object SchedulingConflicts {
    fun detect(day: LocalDate, events: List<AgendaEvent>, bufferMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault()): List<SchedulingConflict> {
        require(bufferMinutes in 0..180) { "Travel buffer must be 0 to 180 minutes" }
        val groups = EventGroups.display(CalendarDisplay.onDay(events, day, zone))
        require(groups.size <= 200) { "Too many events to evaluate conflicts" }
        data class Interval(val start: Instant, val end: Instant)
        val intervals = groups.map { group ->
            val event = group.first()
            if (event.allDay) Interval(Instant.MIN, Instant.MAX) else {
                val start = AgendaEvent.instant(event.start, zone)
                    ?: error("An event has an unsupported start time")
                val end = AgendaEvent.instant(event.end, zone)
                    ?: error("An event has an unsupported end time")
                require(end.isAfter(start)) { "An event has invalid time bounds" }
                Interval(start, end)
            }
        }
        fun overlaps(a: Interval, b: Interval) = a.start.isBefore(b.end) && b.start.isBefore(a.end)
        val conflicts = mutableListOf<SchedulingConflict>()
        for (first in groups.indices) for (second in first + 1 until groups.size) {
            val a = intervals[first]
            val b = intervals[second]
            val direct = overlaps(a, b)
            val buffered = if (direct || bufferMinutes == 0) direct else {
                val gap = bufferMinutes * 60L
                val expanded = Interval(
                    if (a.start == Instant.MIN) Instant.MIN else a.start.minusSeconds(gap),
                    if (a.end == Instant.MAX) Instant.MAX else a.end.plusSeconds(gap))
                overlaps(expanded, b)
            }
            if (buffered) conflicts += SchedulingConflict(groups[first], groups[second],
                if (direct) ConflictKind.OVERLAP else ConflictKind.TRAVEL_BUFFER)
        }
        return conflicts
    }
}
