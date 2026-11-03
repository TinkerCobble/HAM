package com.tinkercobble.ham

import com.tinkercobble.ham.data.MonthLayout
import com.tinkercobble.ham.data.WeekStart
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class WeekStartTest {
    @Test fun SundayAndMondayHeadingsAndSixRowMonths() {
        val march = LocalDate.parse("2026-03-01")
        val sunday = MonthLayout.cells(march, 31, DayOfWeek.SUNDAY)
        val monday = MonthLayout.cells(march, 31, DayOfWeek.MONDAY)
        assertEquals(DayOfWeek.SUNDAY, MonthLayout.weekdays(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, MonthLayout.weekdays(DayOfWeek.SUNDAY).last())
        assertEquals(DayOfWeek.MONDAY, MonthLayout.weekdays(DayOfWeek.MONDAY).first())
        assertEquals(march, sunday.first())
        assertEquals(35, sunday.size)
        assertEquals(LocalDate.parse("2026-02-23"), monday.first())
        assertEquals(42, monday.size)
        assertEquals(LocalDate.parse("2026-04-05"), monday.last())

        val august = MonthLayout.cells(LocalDate.parse("2026-08-01"), 31, DayOfWeek.SUNDAY)
        assertEquals(42, august.size)
        assertEquals(LocalDate.parse("2026-07-26"), august.first())
    }

    @Test fun systemDefaultFollowsLocaleAndExplicitChoicesDoNot() {
        assertEquals(DayOfWeek.SUNDAY, WeekStart.SYSTEM_DEFAULT.firstDay(Locale.US))
        assertEquals(DayOfWeek.MONDAY, WeekStart.SYSTEM_DEFAULT.firstDay(Locale.UK))
        assertEquals(DayOfWeek.SUNDAY, WeekStart.SUNDAY.firstDay(Locale.UK))
        assertEquals(DayOfWeek.MONDAY, WeekStart.MONDAY.firstDay(Locale.US))
    }
}
