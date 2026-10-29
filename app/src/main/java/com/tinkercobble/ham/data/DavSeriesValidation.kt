package com.tinkercobble.ham.data

/** Reject an old screen, changed rule, or resource containing recurrence exceptions. */
object DavSeriesValidation {
    fun requireUnchanged(ics: ByteArray, source: CalendarSource, displayed: AgendaEvent) {
        require(source.provider == CalendarProviderKind.CALDAV &&
            displayed.provider == CalendarProviderKind.CALDAV &&
            displayed.calendarId == source.id &&
            displayed.recurrenceId != null && !displayed.uid.isNullOrBlank()) {
            "This event is not a supported calendar series"
        }
        check(DavEventPayload.simpleSeriesRule(ics, displayed.allDay) == displayed.rrule) {
            "Calendar series changed; refresh before editing"
        }
        val read = IcsEventParser.parse(ics, source,
            DateRange(displayed.date, displayed.date.plusDays(1)))
        val occurrence = read.events.filter {
            it.uid == displayed.uid && it.occursOn(displayed.date)
        }
        check(read.unsupportedRecurring == 0 &&
            occurrence.singleOrNull()?.identity == displayed.identity) {
            "Calendar series changed; refresh before editing"
        }
    }
}
