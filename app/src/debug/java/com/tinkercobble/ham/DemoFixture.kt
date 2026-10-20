package com.tinkercobble.ham

import com.tinkercobble.ham.data.AgendaEvent
import com.tinkercobble.ham.data.CalendarSource
import com.tinkercobble.ham.data.DateRange
import com.tinkercobble.ham.data.TodoEntry
import com.tinkercobble.ham.ui.HamViewModel
import java.time.Instant
import java.time.LocalDate

/** Opt-in screenshot data; compiled only into debug APKs and never contacts HA. */
object DemoFixture {
    @JvmStatic fun seed(model: HamViewModel) {
        val today = LocalDate.now()
        val work = CalendarSource("calendar.sample_work", "Sample Work", 5, "Demo fixture")
        val community = CalendarSource("calendar.community", "Community", 0, "Demo fixture")
        val personal = CalendarSource("calendar.personal", "Personal", 5, "Demo fixture")
        model.connected.value = true
        model.demoPreview.value = true
        model.server.value = "https://demo.invalid"
        model.calendars.value = listOf(work, community, personal)
        model.events.value = listOf(
            event(work, "Planning session", today, 9, 10),
            event(community, "Library visit", today, 14, 15),
            event(personal, "Walk", today.plusDays(1), 8, 9),
            event(work, "Project review", today.plusDays(2), 11, 12),
            event(community, "Community workshop", today.plusDays(4), 16, 17),
            event(personal, "Market", today.plusDays(6), 10, 11),
        )
        model.tasks.value = listOf(TodoEntry("Demo tasks", "Review notes", null, "fixture-1"))
        model.loadedRange.value = DateRange(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1))
        model.lastConnection.value = Instant.now()
        model.lastSync.value = Instant.now()
    }

    private fun event(source: CalendarSource, title: String, day: LocalDate, start: Int, end: Int) =
        AgendaEvent(source.id, source.name, title, "${day}T${start.toString().padStart(2, '0')}:00:00",
            "${day}T${end.toString().padStart(2, '0')}:00:00", false, "", "", "fixture-$title", null)
}
