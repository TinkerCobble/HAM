package com.tinkercobble.ham.data

/** A three-way merge limited to a title-only edit of one nonrecurring event. */
object DavTitleConflict {
    data class Review(val originalTitle: String, val serverTitle: String,
        val proposedTitle: String, val mergedIcs: ByteArray)

    private fun event(bytes: ByteArray, source: CalendarSource): AgendaEvent {
            val parsed = IcsEventParser.parse(bytes, source)
            check(parsed.unsupportedRecurring == 0 && parsed.events.size == 1) {
                "Calendar item needs a different conflict editor"
            }
            val event = parsed.events.single()
            check(event.uid != null && event.recurrenceId == null && event.rrule == null) {
                "Calendar item needs a different conflict editor"
            }
            return event
    }

    fun canReview(base: ByteArray, pending: ByteArray, source: CalendarSource): Boolean =
        runCatching {
            val original = event(base, source)
            val local = event(pending, source)
            original.uid == local.uid &&
                DavEventPayload.updateTitle(base, local.summary).contentEquals(pending)
        }.getOrDefault(false)

    fun review(base: ByteArray, pending: ByteArray, current: ByteArray,
        source: CalendarSource): Review {
        val original = event(base, source)
        val local = event(pending, source)
        val server = event(current, source)
        check(DavEventPayload.updateTitle(base, local.summary).contentEquals(pending)) {
            "This pending change also edits other fields"
        }
        check(original.uid == local.uid && original.uid == server.uid &&
            original.allDay == server.allDay && original.start == server.start &&
            original.end == server.end) { "Calendar identity or time changed on the server" }
        check(server.summary != local.summary) { "The server already has this title; refresh the calendar" }
        return Review(original.summary, server.summary, local.summary,
            DavEventPayload.updateTitle(current, local.summary))
    }
}
