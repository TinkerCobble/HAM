package com.tinkercobble.ham.data

/** A failed source keeps only its previously loaded events that overlap the requested range. */
fun mergeCalendarRead(previous: List<AgendaEvent>, received: List<AgendaEvent>,
    failedIds: Set<String>, range: DateRange): List<AgendaEvent> {
    val retained = previous.filter { event -> event.calendarId in failedIds &&
        range.days.any { day -> event.occursOn(day) } }
    return distinctEvents(received + retained)
}

class RefreshGeneration {
    private var current = 0L
    fun next(): Long = ++current
    fun accepts(generation: Long): Boolean = generation == current
}
