package com.tinkercobble.ham.data

/** A failed source keeps only its previously loaded events that overlap the requested range. */
fun mergeCalendarRead(previous: List<AgendaEvent>, received: List<AgendaEvent>,
    failedIds: Set<String>, range: DateRange): List<AgendaEvent> {
    val retained = previous.filter { event -> event.calendarId in failedIds &&
        range.days.any { day -> event.occursOn(day) } }
    return distinctEvents(received + retained)
}

/** Replace only successfully refreshed sources in the requested range; preserve other cached dates. */
fun mergeCachedCalendarRead(previous: List<AgendaEvent>, received: List<AgendaEvent>,
    succeededIds: Set<String>, range: DateRange): List<AgendaEvent> {
    val retained = previous.filter { event -> event.calendarId !in succeededIds ||
        range.days.none { day -> event.occursOn(day) } }
    return distinctEvents(retained + received)
}

/** On a partial DAV read, replace only events whose provider UID/occurrence was returned. */
fun retainUnverifiedPartialDav(previous: List<AgendaEvent>, partial: List<AgendaEvent>): List<AgendaEvent> {
    val verified = partial.filter { it.provider == CalendarProviderKind.CALDAV && !it.uid.isNullOrBlank() }
        .map { Triple(it.calendarId, it.uid, it.recurrenceId) }.toSet()
    return previous.filterNot { it.provider == CalendarProviderKind.CALDAV &&
        Triple(it.calendarId, it.uid, it.recurrenceId) in verified }
}

/** Remove cached events only when that provider successfully returned its current calendar list. */
fun pruneRemovedCalendars(previous: List<AgendaEvent>, provider: CalendarProviderKind,
    currentIds: Set<String>): List<AgendaEvent> = previous.filterNot {
    it.provider == provider && it.calendarId !in currentIds
}

class RefreshGeneration {
    private var current = 0L
    fun next(): Long = ++current
    fun accepts(generation: Long): Boolean = generation == current
}
