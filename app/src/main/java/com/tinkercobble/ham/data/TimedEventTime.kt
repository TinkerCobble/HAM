package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class TimedEventTime(val start: ZonedDateTime, val end: ZonedDateTime) {
    companion object {
        private val format = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

        fun parse(date: LocalDate, startText: String, endText: String,
            zone: ZoneId = ZoneId.systemDefault()): TimedEventTime {
            fun resolve(value: String): ZonedDateTime {
                val local = date.atTime(LocalTime.parse(value.trim(), format))
                val offsets = zone.rules.getValidOffsets(local)
                require(offsets.size == 1) { "Choose a time outside the daylight-saving change" }
                return ZonedDateTime.ofStrict(local, offsets.single(), zone)
            }
            val start = resolve(startText)
            val end = resolve(endText)
            require(end.isAfter(start)) { "End time must be later on the same day" }
            return TimedEventTime(start, end)
        }
    }
}
