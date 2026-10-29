package com.tinkercobble.ham.data

/** A reviewed metadata-only rebase for one ordinary CalDAV event. */
object DavDetailsConflict {
    data class Review(val eventTitle: String,
        val originalDescription: String, val originalLocation: String,
        val serverDescription: String, val serverLocation: String,
        val proposedDescription: String, val proposedLocation: String,
        val mergedIcs: ByteArray)

    private fun event(bytes: ByteArray, source: CalendarSource): AgendaEvent {
        val parsed = IcsEventParser.parse(bytes, source)
        check(parsed.unsupportedRecurring == 0 && parsed.events.size == 1) {
            "Calendar item needs a different conflict editor"
        }
        return parsed.events.single().also {
            check(it.uid != null && it.recurrenceId == null && it.rrule == null) {
                "Calendar item needs a different conflict editor"
            }
        }
    }

    fun canReview(base: ByteArray, pending: ByteArray, source: CalendarSource): Boolean =
        runCatching {
            val original = event(base, source)
            val local = event(pending, source)
            original.uid == local.uid && original.summary == local.summary &&
                original.start == local.start && original.end == local.end &&
                original.allDay == local.allDay &&
                (original.description != local.description || original.location != local.location) &&
                DavEventPayload.updateDetails(base, local.description, local.location)
                    .contentEquals(pending)
        }.getOrDefault(false)

    fun review(base: ByteArray, pending: ByteArray, current: ByteArray,
        source: CalendarSource): Review {
        check(canReview(base, pending, source)) { "This pending change also edits other fields" }
        val original = event(base, source)
        val local = event(pending, source)
        val server = event(current, source)
        check(original.uid == server.uid && original.allDay == server.allDay &&
            original.start == server.start && original.end == server.end) {
            "Calendar identity or time changed on the server"
        }
        check(server.description != local.description || server.location != local.location) {
            "The server already has these details; refresh the calendar"
        }
        return Review(server.summary, original.description, original.location,
            server.description, server.location, local.description, local.location,
            DavEventPayload.updateDetails(current, local.description, local.location))
    }
}
