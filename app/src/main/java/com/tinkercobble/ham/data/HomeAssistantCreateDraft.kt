package com.tinkercobble.ham.data

/** Keep proposed fields in the same order as AgendaEvent and the HA create command. */
object HomeAssistantCreateDraft {
    fun of(source: CalendarSource, title: String, start: String, end: String,
        allDay: Boolean, description: String, location: String): AgendaEvent {
        val details = EventDraftDetails.of(description, location)
        return AgendaEvent(source.id, source.name, title, start, end, allDay,
            details.description, details.location, null, null, recurrenceKnown = true)
    }
}
