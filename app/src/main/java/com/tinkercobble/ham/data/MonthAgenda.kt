package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.YearMonth

data class MonthAgendaSection(val month: YearMonth, val events: List<AgendaEvent>,
    val expandedByDefault: Boolean)

/** The displayed month and the next eleven months. Empty months have no section. */
object MonthAgenda {
    fun range(anchor: LocalDate): DateRange {
        val first = YearMonth.from(anchor).atDay(1)
        return DateRange(first, first.plusMonths(12))
    }

    fun sections(anchor: LocalDate, events: List<AgendaEvent>,
        firstVisibleDay: LocalDate = YearMonth.from(anchor).atDay(1)): List<MonthAgendaSection> {
        val first = YearMonth.from(anchor)
        return (0L..11L).mapNotNull { offset ->
            val month = first.plusMonths(offset)
            val start = if (offset == 0L && YearMonth.from(firstVisibleDay) == month)
                firstVisibleDay else month.atDay(1)
            val range = DateRange(start, month.plusMonths(1).atDay(1))
            val matching = events.filter { it.occursIn(range) }
                .sortedWith(compareBy({ it.date }, { it.start }, { it.summary }, { it.identity }))
            if (matching.isEmpty()) null else MonthAgendaSection(month, matching, offset < 3)
        }
    }
}
