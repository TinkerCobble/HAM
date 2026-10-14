package com.tinkercobble.ham.data

/** Calendar identity is always the Home Assistant entity ID, never its display name. */
data class CalendarPreferences(
    val favorites: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val defaultId: String? = null,
    val recent: List<String> = emptyList(),
) {
    fun used(id: String) = copy(recent = (listOf(id) + recent.filterNot { it == id }).take(8))
    fun recentString() = recent.joinToString("\n")
    companion object {
        fun fromStored(favorites: Set<String>?, hidden: Set<String>?, defaultId: String?, recent: String?) =
            CalendarPreferences(favorites?.toSet() ?: emptySet(), hidden?.toSet() ?: emptySet(),
                defaultId, recent.orEmpty().split('\n').filter { it.isNotBlank() }.distinct().take(8))
    }
}

object CalendarSelection {
    fun writable(calendars: List<CalendarSource>) = calendars.filter { it.canCreate }.distinctBy { it.id }
    fun resolveWritable(id: String?, calendars: List<CalendarSource>): CalendarSource? =
        id?.let { wanted -> calendars.singleOrNull { it.id == wanted && it.canCreate } }

    fun search(calendars: List<CalendarSource>, query: String): List<CalendarSource> {
        val term = query.trim()
        return calendars.filter { term.isEmpty() || it.name.contains(term, true) ||
            it.id.contains(term, true) || it.source.contains(term, true) }
    }

    fun groups(calendars: List<CalendarSource>, prefs: CalendarPreferences): List<Pair<String, List<CalendarSource>>> {
        val favorite = calendars.filter { it.id in prefs.favorites }
        val recent = prefs.recent.mapNotNull { id -> calendars.find { it.id == id && it.id !in prefs.favorites } }
        val assigned = (favorite + recent).map { it.id }.toSet()
        val all = calendars.filterNot { it.id in assigned }
        return listOf("Favorites" to favorite, "Recently Used" to recent, "All Calendars" to all)
            .filter { it.second.isNotEmpty() }
    }
}
