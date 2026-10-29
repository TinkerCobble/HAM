package com.tinkercobble.ham.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class SuggestedTime(val start: ZonedDateTime, val end: ZonedDateTime)

/** Local-only availability over the already selected and successfully loaded events. */
object FindTime {
    fun suggest(day: LocalDate, events: List<AgendaEvent>, opening: LocalTime,
        closing: LocalTime, durationMinutes: Int, bufferMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault()): List<SuggestedTime> {
        require(opening.isBefore(closing)) { "Closing time must follow opening time" }
        require(durationMinutes in 15..480 && durationMinutes % 15 == 0) {
            "Duration must be 15 to 480 minutes, in 15-minute steps"
        }
        require(bufferMinutes in 0..180) { "Travel buffer must be 0 to 180 minutes" }
        fun exact(time: LocalTime): ZonedDateTime? {
            val local = day.atTime(time)
            val offsets = zone.rules.getValidOffsets(local)
            return offsets.singleOrNull()?.let { ZonedDateTime.ofStrict(local, it, zone) }
        }
        val endWindow = exact(closing) ?: error("Closing time falls in a daylight-saving change")
        val occupied = events.mapNotNull { event ->
            if (event.allDay) {
                val first = LocalDate.parse(event.start.take(10))
                val last = LocalDate.parse(event.end.take(10))
                if (!day.isBefore(first) && day.isBefore(last))
                    Instant.MIN to Instant.MAX else null
            } else {
                val start = AgendaEvent.instant(event.start, zone)
                    ?: error("An event has an unsupported start time")
                val end = AgendaEvent.instant(event.end, zone)
                    ?: error("An event has an unsupported end time")
                require(end.isAfter(start)) { "An event has invalid time bounds" }
                start.minusSeconds(bufferMinutes * 60L) to
                    end.plusSeconds(bufferMinutes * 60L)
            }
        }
        val result = mutableListOf<SuggestedTime>()
        var cursor = opening
        while (cursor.isBefore(closing) && result.size < 20) {
            val start = exact(cursor)
            val endTime = cursor.plusMinutes(durationMinutes.toLong())
            val end = if (endTime.isAfter(cursor)) exact(endTime) else null
            if (start != null && end != null && !end.isAfter(endWindow) &&
                occupied.none { (busyStart, busyEnd) ->
                    start.toInstant().isBefore(busyEnd) && end.toInstant().isAfter(busyStart)
                }) result += SuggestedTime(start, end)
            cursor = cursor.plusMinutes(15)
            if (cursor == LocalTime.MIDNIGHT) break
        }
        return result
    }
}
