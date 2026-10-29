package com.tinkercobble.ham.data

import java.time.LocalDate

/** Export every visible source record; presentation mirror groups never erase a calendar copy. */
object IcsDayExport {
    fun events(visible: List<AgendaEvent>, day: LocalDate): List<AgendaEvent> =
        CalendarDisplay.onDay(visible, day)
}
