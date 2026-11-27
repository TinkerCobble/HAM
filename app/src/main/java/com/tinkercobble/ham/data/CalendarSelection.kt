package com.tinkercobble.ham.data

/** Calendar identity uses the provider's stable ID, never its display name. */
data class CalendarPreferences(
    val favorites: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val defaultId: String? = null,
    val recent: List<String> = emptyList(),
    val colors: Map<String, String> = emptyMap(),
    val view: CalendarView = CalendarView.WEEK,
    val mirrorHidden: Set<String> = emptySet(),
    val order: Map<String, Int> = emptyMap(),
    val defaultView: CalendarView = CalendarView.MONTH,
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
                       colorText: String? = null, viewText: String? = null,
                       mirrorHidden: Set<String>? = null, orderText: String? = null,
                       defaultViewText: String? = null) =
            CalendarPreferences(favorites?.toSet() ?: emptySet(), hidden?.toSet() ?: emptySet(),
                defaultId, recent.orEmpty().split('\n').filter { it.isNotBlank() }.distinct().take(8),
                colorText.orEmpty().split('\n').mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2 && (parts[0].startsWith("calendar.") ||
                        parts[0].matches(Regex("android:[0-9]+")) ||
                        parts[0].matches(Regex("dav:[0-9a-fA-F-]{36}:[0-9a-f]{24}")) ||
                        parts[0].matches(Regex("ics:[0-9a-fA-F-]{36}"))) &&
                        Regex("#[0-9A-Fa-f]{6}").matches(parts[1])) parts[0] to parts[1] else null
                }.toMap(), CalendarView.entries.find { it.name == viewText } ?: CalendarView.WEEK,
                mirrorHidden?.toSet() ?: emptySet(),
                orderText.orEmpty().lineSequence().mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    val position = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 2 && parts[0].isNotBlank() &&
                        position != null && position in 0..10_000) parts[0] to position else null
                }.toMap(), CalendarView.entries.find { it.name == defaultViewText } ?: CalendarView.MONTH)
    }
}

object CalendarSelection {
    fun managementSection(source: CalendarSource, prefs: CalendarPreferences,
        visible: Boolean): String = when {
        !visible -> "Hidden Calendars"
        source.id in prefs.favorites || source.id == prefs.defaultId || source.id in prefs.recent ->
            "My Calendars"
        source.provider == CalendarProviderKind.HOME_ASSISTANT -> "Home Assistant"
        else -> "Other Calendars"
    }
    /** Previously seen HA IDs keep their choices; newly discovered IDs start hidden. */
    fun hideNewHomeAssistantCalendars(prefs: CalendarPreferences, discovered: Set<String>,
        known: Set<String>?, cached: Set<String>, preserveLegacySession: Boolean = false): CalendarPreferences {
        val previous = known ?: if (preserveLegacySession) discovered else cached
        return prefs.copy(hidden = prefs.hidden + (discovered - previous))
    }
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

    /** Retain temporarily missing bound rows; clear choices if a row is reused by another calendar. */
    fun reconcileAndroidChoices(prefs: CalendarPreferences, visible: Set<String>,
        current: List<CalendarSource>, prior: List<CalendarSource>,
        bound: Map<String, String>): AndroidReconciliation {
        val now = current.filter { it.provider == CalendarProviderKind.ANDROID }.associateBy { it.id }
        val old = prior.filter { it.provider == CalendarProviderKind.ANDROID }.associateBy { it.id }
        val choices = (visible + prefs.favorites + prefs.hidden + prefs.recent + prefs.colors.keys +
            prefs.mirrorHidden + prefs.order.keys +
            listOfNotNull(prefs.defaultId)).filter { it.startsWith("android:") }.toSet()
        val stale = choices.filterTo(mutableSetOf()) { id ->
            val source = now[id]
            val fingerprint = source?.identityFingerprint
            if (source == null) bound[id] == null && old[id]?.identityFingerprint == null
            else if (fingerprint == null) true
            else bound[id]?.let { it != fingerprint } ?: old[id]?.let { previous ->
                previous.identityFingerprint?.let { it != fingerprint } ?:
                    (previous.name != source.name || previous.source != source.source)
            } ?: true
        }
        val clean = prefs.copy(favorites = prefs.favorites - stale,
            hidden = prefs.hidden - stale,
            defaultId = prefs.defaultId?.takeUnless { it in stale },
            recent = prefs.recent.filterNot { it in stale },
            colors = prefs.colors - stale, mirrorHidden = prefs.mirrorHidden - stale,
            order = prefs.order - stale)
        val identities = now.mapNotNull { (id, source) ->
            source.identityFingerprint?.let { id to it } }.toMap() +
            choices.filter { it !in now && it !in stale }.mapNotNull { id ->
                (bound[id] ?: old[id]?.identityFingerprint)?.let { id to it } }.toMap()
        return AndroidReconciliation(clean, visible - stale, identities, stale.isNotEmpty())
    }

    fun visibleEvents(events: List<AgendaEvent>, prefs: CalendarPreferences,
        androidEnabled: Boolean, androidPermissionGranted: Boolean, androidVisibleIds: Set<String>,
        includeHidden: Boolean = false, davVisibleIds: Set<String> = emptySet(),
        icsVisibleIds: Set<String> = emptySet()): List<AgendaEvent> = events.filter { event ->
        val androidAllowed = event.provider != CalendarProviderKind.ANDROID ||
            (androidEnabled && androidPermissionGranted && event.calendarId in androidVisibleIds)
        val davAllowed = event.provider != CalendarProviderKind.CALDAV || event.calendarId in davVisibleIds
        val icsAllowed = event.provider != CalendarProviderKind.ICS || event.calendarId in icsVisibleIds
        androidAllowed && davAllowed && icsAllowed &&
            (includeHidden || event.calendarId !in prefs.hidden && event.calendarId !in prefs.mirrorHidden)
    }

    fun writable(calendars: List<CalendarSource>) = calendars.filter { it.canCreate }.distinctBy { it.id }
    fun resolveWritable(id: String?, calendars: List<CalendarSource>): CalendarSource? =
        id?.let { wanted -> calendars.singleOrNull { it.id == wanted && it.canCreate } }
    fun resolveVisibleWritable(id: String?, calendars: List<CalendarSource>,
        visible: (CalendarSource) -> Boolean): CalendarSource? =
        resolveWritable(id, calendars)?.takeIf(visible)

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
