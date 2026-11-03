package com.tinkercobble.ham.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

enum class WeekStart(val label: String) {
    SYSTEM_DEFAULT("System Default"), SUNDAY("Sunday"), MONDAY("Monday");

    fun firstDay(locale: Locale = Locale.getDefault()): DayOfWeek = when (this) {
        SYSTEM_DEFAULT -> WeekFields.of(locale).firstDayOfWeek
        SUNDAY -> DayOfWeek.SUNDAY
        MONDAY -> DayOfWeek.MONDAY
    }
}

/** Presentation only: the returned dates never alter events or recurrence rules. */
object MonthLayout {
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> =
        (0..6).map { firstDay.plus(it.toLong()) }

    fun cells(first: LocalDate, dayCount: Int, firstDay: DayOfWeek): List<LocalDate> {
        require(dayCount in 28..31 && first.dayOfMonth == 1)
        val offset = (first.dayOfWeek.value - firstDay.value + 7) % 7
        val count = ((offset + dayCount + 6) / 7) * 7
        return (0 until count).map { first.plusDays((it - offset).toLong()) }
    }
}

class WeekStartPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("ham-ui-preferences", Context.MODE_PRIVATE)
    fun load(): WeekStart = WeekStart.entries.find { it.name == prefs.getString("start_week_on", null) }
        ?: WeekStart.MONDAY // Preserve the pre-setting month layout on existing installs.
    fun save(value: WeekStart) {
        check(prefs.edit().putString("start_week_on", value.name).commit()) {
            "Could not save calendar week-start preference"
        }
    }
}
