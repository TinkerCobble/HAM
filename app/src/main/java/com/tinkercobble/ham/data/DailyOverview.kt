package com.tinkercobble.ham.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A presentation summary of the already-selected agenda. It never changes source events. */
data class DailyOverview(val total: Int, val allDay: Int, val timed: Int,
    val nextTimed: AgendaEvent?) {
    companion object {
        fun forDay(visibleEvents: List<AgendaEvent>, day: LocalDate,
            now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): DailyOverview {
            val displayed = EventGroups.display(CalendarDisplay.onDay(visibleEvents, day, zone))
                .map { it.first() }
            val timed = displayed.filterNot { it.allDay }
            return DailyOverview(displayed.size, displayed.count { it.allDay }, timed.size,
                timed.firstOrNull { event ->
                    val start = AgendaEvent.instant(event.start, zone)
                    start != null && start.isAfter(now)
                })
        }
    }
}
