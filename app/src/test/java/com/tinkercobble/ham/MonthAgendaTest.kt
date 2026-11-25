package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarPreferences
import com.tinkercobble.ham.data.CalendarView
import com.tinkercobble.ham.data.MonthAgenda
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthAgendaTest {
    private fun event(day: String, end: String = LocalDate.parse(day).plusDays(1).toString()) =
        AgendaEvent("calendar.synthetic", "Fictional", "Fictional appointment", day, end,
            true, "", "", null, null)

    @Test fun twelveMonthRangeCrossesYearAndExcludesThirteenthMonth() {
        val start = LocalDate.of(2026, 11, 25)
        assertEquals(LocalDate.of(2026, 11, 1), MonthAgenda.range(start).start)
        assertEquals(LocalDate.of(2027, 11, 1), MonthAgenda.range(start).endExclusive)
        val sections = MonthAgenda.sections(start, listOf(
            event("2026-11-02"), event("2027-01-05"), event("2027-02-03"),
            event("2027-10-31"), event("2027-11-01")))
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2027, 1),
            YearMonth.of(2027, 2), YearMonth.of(2027, 10)), sections.map { it.month })
        assertEquals(listOf(true, true, false, false), sections.map { it.expandedByDefault })
    }

    @Test fun spanningEventAppearsInBothRelevantMonthsAndEmptyMonthsAreAbsent() {
        val sections = MonthAgenda.sections(LocalDate.of(2026, 11, 1),
            listOf(event("2026-11-30", "2026-12-02")))
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12)), sections.map { it.month })
        assertTrue(sections.all { it.events.size == 1 })
        assertTrue(MonthAgenda.sections(LocalDate.of(2026, 11, 1), emptyList()).isEmpty())
    }

    @Test fun firstMonthStartsAtSelectedDayWhileFutureMonthsRemainComplete() {
        val events = listOf(event("2026-11-01"), event("2026-11-25"),
            event("2026-11-26"), event("2026-12-01"))
        val sections = MonthAgenda.sections(LocalDate.of(2026, 11, 25), events,
            LocalDate.of(2026, 11, 25))
        assertEquals(listOf("2026-11-25", "2026-11-26"),
            sections.first().events.map { it.date.toString() })
        assertEquals(1, sections[1].events.size)
        assertEquals(3, MonthAgenda.sections(LocalDate.of(2026, 11, 25), events,
            LocalDate.of(2026, 11, 1)).first().events.size)
    }

    @Test fun monthIsDefaultAndSavedChoiceCanBeRestoredIndependentlyOfCurrentView() {
        assertEquals(CalendarView.MONTH, CalendarPreferences().defaultView)
        assertEquals(CalendarView.WEEK, CalendarView.MONTH.next())
        assertEquals(CalendarView.THREE_DAYS, CalendarView.WEEK.next())
        assertEquals(CalendarView.MONTH, CalendarView.THREE_DAYS.next())
        val restored = CalendarPreferences.fromStored(emptySet(), emptySet(), null, null,
            viewText = CalendarView.THREE_DAYS.name, defaultViewText = CalendarView.WEEK.name)
        assertEquals(CalendarView.THREE_DAYS, restored.view)
        assertEquals(CalendarView.WEEK, restored.defaultView)
    }
}
