package com.tinkercobble.ham.data

import java.time.LocalDate
import java.time.ZoneId

data class WidgetAgenda(val total: Int, val allDay: Int, val details: List<String>) {
    companion object {
        /** Apply the same opt-in visibility rules as HAM before copying anything to a launcher. */
        fun forDay(events: List<AgendaEvent>, preferences: CalendarPreferences,
            androidEnabled: Boolean, androidPermissionGranted: Boolean, androidIds: Set<String>,
            davIds: Set<String>, icsIds: Set<String>, showDetails: Boolean,
            day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): WidgetAgenda {
            val visible = CalendarSelection.visibleEvents(events, preferences, androidEnabled,
                androidPermissionGranted, androidIds, davVisibleIds = davIds, icsVisibleIds = icsIds)
            val groups = EventGroups.display(CalendarDisplay.onDay(visible, day, zone))
            val displayed = groups.map { it.first() }
            return WidgetAgenda(displayed.size, displayed.count { it.allDay },
                if (!showDetails) emptyList() else displayed.take(3).map { event ->
                    val time = if (event.allDay) "All day" else CalendarDisplay.time(event.start, zone)
                    val title = event.summary.replace(Regex("\\s+"), " ").trim()
                        .ifBlank { "Untitled event" }.take(80)
                    "$time · $title"
                })
        }
    }
}
