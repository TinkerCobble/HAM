package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.YearMonth

/** A bounded finite series. Timed writes require separate exact time-zone checks. */
enum class RepeatFrequency(val label: String) { DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

data class RepeatSpec(val frequency: RepeatFrequency, val count: Int) {
    init { require(count in 2..52) { "Choose 2 to 52 occurrences" } }
    val rrule: String get() = "FREQ=${frequency.name};COUNT=$count"
    val label: String get() = "${frequency.label} for $count occurrences"

    /** Dates for the finite rule; a missing monthly day does not consume COUNT. */
    fun occurrenceDates(first: LocalDate): List<LocalDate> = when (frequency) {
        RepeatFrequency.DAILY -> (0 until count).map { first.plusDays(it.toLong()) }
        RepeatFrequency.WEEKLY -> (0 until count).map { first.plusWeeks(it.toLong()) }
        RepeatFrequency.MONTHLY -> buildList {
            var month = YearMonth.from(first)
            while (size < count) {
                if (first.dayOfMonth <= month.lengthOfMonth()) add(month.atDay(first.dayOfMonth))
                month = month.plusMonths(1)
            }
        }
    }

    companion object {
        /** Only the finite rules HAM can safely edit as a whole series. */
        fun supported(rule: String?): RepeatSpec? {
            val parts = rule?.split(';') ?: return null
            if (parts.size !in 2..3) return null
            val values = mutableMapOf<String, String>()
            for (part in parts) {
                val pair = part.split('=', limit = 2)
                if (pair.size != 2 || pair[0] !in setOf("FREQ", "COUNT", "WKST") ||
                    values.put(pair[0], pair[1]) != null) return null
            }
            if (values.size == 3 && values["WKST"] !in
                setOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")) return null
            if (values.size == 2 && values.containsKey("WKST")) return null
            val frequency = runCatching {
                RepeatFrequency.valueOf(values["FREQ"] ?: return null)
            }.getOrNull() ?: return null
            val countText = values["COUNT"] ?: return null
            if (!countText.matches(Regex("[0-9]+"))) return null
            val count = countText.toIntOrNull() ?: return null
            // WKST does not alter these single-day, interval-one rules. Some sync adapters add it.
            return runCatching { RepeatSpec(frequency, count) }.getOrNull()
        }
    }
}
