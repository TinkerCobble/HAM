package com.tinkercobble.ham.data

/** Calendar identity uses the provider's stable ID, never its display name. */
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
                    if (parts.size == 2 && (parts[0].startsWith("calendar.") ||
                        parts[0].matches(Regex("android:[0-9]+")) ||
                        parts[0].matches(Regex("dav:[0-9a-fA-F-]{36}:[0-9a-f]{24}")) ||
                        parts[0].matches(Regex("ics:[0-9a-fA-F-]{36}"))) &&
                        Regex("#[0-9A-Fa-f]{6}").matches(parts[1])) parts[0] to parts[1] else null
                }.toMap(), CalendarView.entries.find { it.name == viewText } ?: CalendarView.WEEK)
    }
}

object CalendarSelection {
    data class AndroidReconciliation(val preferences: CalendarPreferences,
        val visibleIds: Set<String>, val fingerprints: Map<String, String>,
        val choicesRemoved: Boolean)

    fun parseAndroidFingerprints(text: String?): Map<String, String> = text.orEmpty().lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2 && parts[0].matches(Regex("android:[0-9]+")) &&
                parts[1].matches(Regex("[0-9a-f]{64}"))) parts[0] to parts[1] else null
        }.toMap()

    fun encodeAndroidFingerprints(values: Map<String, String>): String = values.entries
        .sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    /** Clear only Android choices whose row vanished or no longer matches its bound calendar. */
    fun reconcileAndroidChoices(prefs: CalendarPreferences, visible: Set<String>,
        current: List<CalendarSource>, prior: List<CalendarSource>,
        bound: Map<String, String>): AndroidReconciliation {
        val now = current.filter { it.provider == CalendarProviderKind.ANDROID }.associateBy { it.id }
        val old = prior.filter { it.provider == CalendarProviderKind.ANDROID }.associateBy { it.id }
        val choices = (visible + prefs.favorites + prefs.hidden + prefs.recent + prefs.colors.keys +
            listOfNotNull(prefs.defaultId)).filter { it.startsWith("android:") }.toSet()
        val stale = choices.filterTo(mutableSetOf()) { id ->
            val source = now[id]
            val fingerprint = source?.identityFingerprint
            if (source == null || fingerprint == null) true
            else bound[id]?.let { it != fingerprint } ?: old[id]?.let { previous ->
                previous.identityFingerprint?.let { it != fingerprint } ?:
                    (previous.name != source.name || previous.source != source.source)
            } ?: true
        }
        val clean = prefs.copy(favorites = prefs.favorites - stale,
            hidden = prefs.hidden - stale,
            defaultId = prefs.defaultId?.takeUnless { it in stale },
            recent = prefs.recent.filterNot { it in stale },
            colors = prefs.colors - stale)
        val identities = now.mapNotNull { (id, source) ->
            source.identityFingerprint?.let { id to it } }.toMap()
        return AndroidReconciliation(clean, visible - stale, identities, stale.isNotEmpty())
    }

    fun pruneMissingAndroidChoices(prefs: CalendarPreferences, selected: Set<String>,
        available: Set<String>): Pair<CalendarPreferences, Set<String>> {
        val remaining = selected.filterTo(mutableSetOf()) { it in available }
        val cleanPrefs = if (prefs.defaultId?.startsWith("android:") == true &&
            prefs.defaultId !in available) prefs.copy(defaultId = null) else prefs
        return cleanPrefs to remaining
    }

    fun visibleEvents(events: List<AgendaEvent>, prefs: CalendarPreferences,
        androidEnabled: Boolean, androidPermissionGranted: Boolean, androidVisibleIds: Set<String>,
        includeHidden: Boolean = false, davVisibleIds: Set<String> = emptySet(),
        icsVisibleIds: Set<String> = emptySet()): List<AgendaEvent> = events.filter { event ->
        val androidAllowed = event.provider != CalendarProviderKind.ANDROID ||
            (androidEnabled && androidPermissionGranted && event.calendarId in androidVisibleIds)
        val davAllowed = event.provider != CalendarProviderKind.CALDAV || event.calendarId in davVisibleIds
        val icsAllowed = event.provider != CalendarProviderKind.ICS || event.calendarId in icsVisibleIds
        androidAllowed && davAllowed && icsAllowed && (includeHidden || event.calendarId !in prefs.hidden)
    }

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
