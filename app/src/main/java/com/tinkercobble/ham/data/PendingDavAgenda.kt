package com.tinkercobble.ham.data

/** Display projection only. The encrypted pending queue remains the source of truth. */
object PendingDavAgenda {
    data class Draft(val event: AgendaEvent, val state: DavPendingState)

    fun drafts(changes: List<PendingDavMutation>, sources: List<CalendarSource>,
        range: DateRange? = null): List<Draft> =
        changes.asSequence().filter {
            (it.operation == DavOperation.CREATE || it.operation == DavOperation.UPDATE) && it.ics != null
        }
            .flatMap { change ->
                val source = sources.firstOrNull { it.id == change.calendarId }
                    ?: CalendarSource(change.calendarId, "Pending calendar", 0,
                        provider = CalendarProviderKind.CALDAV)
                runCatching {
                    val read = IcsEventParser.parse(checkNotNull(change.ics), source, range)
                    require(read.unsupportedRecurring == 0)
                    read.events.filter { event -> event.recurrenceId == null || range == null ||
                        range.days.any { event.occursOn(it) } }
                        .map { Draft(it, change.state) }
                }.getOrDefault(emptyList()).asSequence()
            }.toList()

    private data class Identity(val calendarId: String, val uid: String?, val title: String,
        val allDay: Boolean, val start: String, val end: String)

    private fun identity(event: AgendaEvent) = Identity(event.calendarId, event.uid,
        event.summary, event.allDay,
        if (event.allDay) event.start.take(10) else AgendaEvent.instant(event.start)?.toString() ?: event.start,
        if (event.allDay) event.end.take(10) else AgendaEvent.instant(event.end)?.toString() ?: event.end)

    /** Prefer a matching confirmed server copy without hiding a differing local draft. */
    fun merge(server: List<AgendaEvent>, drafts: List<Draft>): List<AgendaEvent> {
        val serverIds = server.filter { it.provider == CalendarProviderKind.CALDAV }
            .mapTo(mutableSetOf(), ::identity)
        return server + drafts.map { it.event }.filterNot { identity(it) in serverIds }
    }

    fun stateFor(event: AgendaEvent, drafts: List<Draft>): DavPendingState? {
        if (event.provider != CalendarProviderKind.CALDAV) return null
        val wanted = identity(event)
        return drafts.firstOrNull { identity(it.event) == wanted }?.state
    }

    fun hasPendingResource(event: AgendaEvent, drafts: List<Draft>): Boolean =
        event.provider == CalendarProviderKind.CALDAV && !event.uid.isNullOrBlank() &&
            drafts.any { it.event.calendarId == event.calendarId && it.event.uid == event.uid }
}
