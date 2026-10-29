package com.tinkercobble.ham.data

/** Presentation-only grouping of one exact Home Assistant and one Android copy. */
object EventGroups {
    private data class Match(val title: String, val start: String, val end: String,
        val allDay: Boolean, val location: String)

    private fun match(event: AgendaEvent): Match? {
        if (event.summary.isBlank()) return null
        val start = if (event.allDay) event.start.take(10) else
            AgendaEvent.instant(event.start)?.toString() ?: return null
        val end = if (event.allDay) event.end.take(10) else
            AgendaEvent.instant(event.end)?.toString() ?: return null
        return Match(event.summary.trim(), start, end, event.allDay,
            event.location.trim().replace(Regex("\\s+"), " "))
    }

    fun display(events: List<AgendaEvent>): List<List<AgendaEvent>> {
        val candidates = events.withIndex().mapNotNull { (index, event) ->
            match(event)?.let { it to index }
        }.groupBy({ it.first }, { it.second })
        val paired = mutableMapOf<Int, Int>()
        candidates.values.forEach { indexes ->
            if (indexes.size == 2 && setOf(events[indexes[0]].provider,
                    events[indexes[1]].provider) ==
                setOf(CalendarProviderKind.HOME_ASSISTANT, CalendarProviderKind.ANDROID)) {
                paired[indexes[0]] = indexes[1]
                paired[indexes[1]] = indexes[0]
            }
        }
        val consumed = mutableSetOf<Int>()
        return events.indices.mapNotNull { index ->
            if (!consumed.add(index)) return@mapNotNull null
            val other = paired[index]
            if (other != null) {
                consumed += other
                listOf(events[index], events[other])
            } else listOf(events[index])
        }
    }
}
