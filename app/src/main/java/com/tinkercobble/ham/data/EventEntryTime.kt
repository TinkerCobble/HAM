package com.tinkercobble.ham.data

import java.time.LocalTime

/** Accepts common human time entry while keeping provider writes in 24-hour HH:mm. */
object EventEntryTime {
    fun parse(input: String): LocalTime? {
        val compact = input.trim().lowercase().replace(" ", "")
        val match = Regex("^(\\d{1,2})(?::?(\\d{2}))?(a|am|p|pm)?$").matchEntire(compact)
            ?: Regex("^(\\d{1,2})(\\d{2})(a|am|p|pm)?$").matchEntire(compact)
            ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        val suffix = match.groupValues[3]
        val normalizedHour = if (suffix.isNotBlank()) {
            if (hour !in 1..12) return null
            hour % 12 + if (suffix.startsWith('p')) 12 else 0
        } else {
            if (hour !in 0..23) return null
            hour
        }
        return LocalTime.of(normalizedHour, minute)
    }

    fun suggestedEnd(start: LocalTime, minutes: Int = 60): LocalTime? =
        start.plusMinutes(minutes.toLong()).takeIf { it.isAfter(start) }
}
