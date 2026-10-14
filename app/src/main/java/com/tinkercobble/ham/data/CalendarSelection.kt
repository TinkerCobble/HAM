package com.tinkercobble.ham.data

/** Calendar identity is always the Home Assistant entity ID, never its display name. */
data class CalendarPreferences(
    val favorites: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val defaultId: String? = null,
    val recent: List<String> = emptyList(),
    val colors: Map<String, String> = emptyMap(),
    val view: CalendarView = CalendarView.WEEK,
) {
    fun used(id: String) = copy(recent = (listOf(id) + recent.filterNot { it == id }).take(8))
    fun recentString() = recent.joinToString("\n")
    fun colorString() = colors.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }
    fun showAll(available: List<CalendarSource>) = copy(hidden = hidden - available.map { it.id }.toSet())
    fun hideAll(available: List<CalendarSource>) = copy(hidden = hidden + available.map { it.id })
    fun setColor(id: String, color: String?) = copy(colors = if (color == null) colors - id
        else colors + (id to color))
    companion object {
        fun fromStored(favorites: Set<String>?, hidden: Set<String>?, defaultId: String?, recent: String?,
                       colorText: String? = null, viewText: String? = null) =
            CalendarPreferences(favorites?.toSet() ?: emptySet(), hidden?.toSet() ?: emptySet(),
                defaultId, recent.orEmpty().split('\n').filter { it.isNotBlank() }.distinct().take(8),
                colorText.orEmpty().split('\n').mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2 && parts[0].startsWith("calendar.") &&
                        Regex("#[0-9A-Fa-f]{6}").matches(parts[1])) parts[0] to parts[1] else null
                }.toMap(), CalendarView.entries.find { it.name == viewText } ?: CalendarView.WEEK)
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
