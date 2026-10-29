package com.tinkercobble.ham.data

import java.util.Locale

/** Read-only, on-device hints. A matching name never merges identities or changes selection. */
object CalendarSourceOverview {
    data class Counts(val homeAssistant: Int, val android: Int, val calDav: Int, val ics: Int = 0) {
        val total get() = homeAssistant + android + calDav + ics
        fun label(): String = "$total calendars: $homeAssistant Home Assistant, $android Android, $calDav CalDAV/iCloud, $ics ICS"
    }

    fun counts(sources: List<CalendarSource>) = Counts(
        sources.count { it.provider == CalendarProviderKind.HOME_ASSISTANT },
        sources.count { it.provider == CalendarProviderKind.ANDROID },
        sources.count { it.provider == CalendarProviderKind.CALDAV },
        sources.count { it.provider == CalendarProviderKind.ICS })

    /** Review hints only. Every member retains its own identity, selection and events. */
    fun possibleNameGroups(sources: List<CalendarSource>): List<List<CalendarSource>> =
        sources.groupBy { it.name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .filterKeys { it.isNotBlank() }
            .values.mapNotNull { group -> group.distinctBy { it.id }.takeIf { it.size > 1 } }
            .sortedBy { it.first().name.trim().lowercase(Locale.ROOT) }
}
